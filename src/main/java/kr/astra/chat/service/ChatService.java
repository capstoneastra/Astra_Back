package kr.astra.chat.service;

import java.util.*;
import java.util.function.Consumer;
import kr.astra.chat.dto.ChatResponse;
import kr.astra.chat.entity.ChatRole;
import kr.astra.global.exception.*;
import kr.astra.global.security.CurrentUser;
import kr.astra.llm.client.LlmClient;
import kr.astra.llm.prompt.PromptLoader;
import kr.astra.llm.service.*;
import kr.astra.requirement.dto.RequirementResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j @Service @RequiredArgsConstructor
public class ChatService {
    private final ChatPersistence persistence;
    private final ConditionExtractionService extraction;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final StructuredJson json;
    private final kr.astra.equipment.service.EquipmentQueryService equipment;
    /** 상담 답변에 소개할 DB 장비 수 */
    private static final int SUGGEST_LIMIT = 5;
    /** 메시지 하나(조건 추출 + 답변)에 AI 를 기다리는 최대 시간. 넘으면 AI_UPSTREAM_ERROR(502) */
    @org.springframework.beans.factory.annotation.Value("${astra.ai.chat-timeout-seconds:25}")
    private long chatTimeoutSeconds = 25;
    /** 그중 조건 추출에 쓸 수 있는 시간. 넘으면 이전 조건으로 답변을 이어감 */
    @org.springframework.beans.factory.annotation.Value("${astra.ai.chat-extraction-seconds:10}")
    private long extractionTimeoutSeconds = 10;
    private record Extracted(String testTypeCode, Object conditions, Map<String,Object> metadata) {}

    public ChatResponse.Exchange send(CurrentUser user, Long id, String content, Consumer<String> onText) {
        var turn = persistence.begin(user,id,content);
        return kr.astra.llm.client.LlmDeadline.within(java.time.Duration.ofSeconds(chatTimeoutSeconds),
                () -> answer(user,turn,onText));
    }

    private ChatResponse.Exchange answer(CurrentUser user, ChatPersistence.Turn turn, Consumer<String> onText) {
        try {
            var transcript = turn.transcript();
            var extracted = extract(transcript);
            if (Thread.currentThread().isInterrupted()) throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
            String prompt = prompts.load("consultation");
            var suggested = suggestEquipment(user, extracted.testTypeCode());
            String payload = json.write(Map.of("messages",transcript.messages().stream()
                    .map(m -> Map.of("role",m.role(),"content",m.content())).toList(),"conditions",extracted.conditions(),
                    "equipmentCandidates",suggested.stream().map(ChatService::brief).toList()));
            var request = new LlmClient.Request(prompt,payload,null,"low");
            var reply = onText == null ? llm.complete(request) : llm.stream(request,onText);
            if (Thread.currentThread().isInterrupted()) throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
            // 프롬프트·전체 대화(promptInput)·원응답은 매 턴 누적되어 메타데이터가 제곱으로 커지므로 저장하지 않음
            Map<String,Object> metadata = new LinkedHashMap<>(extracted.metadata());
            metadata.put("extractedConditions",extracted.conditions());
            metadata.put("suggestedTestType",extracted.testTypeCode());
            metadata.put("suggestedEquipmentIds",suggested.stream().map(kr.astra.equipment.dto.EquipmentCandidate::id).toList());
            metadata.put("conversation",Map.of("model",reply.model(),"requestId",reply.requestId(),
                    "inputTokens",reply.inputTokens(),"outputTokens",reply.outputTokens(),"promptHash",PromptLoader.fingerprint(prompt)));
            return persistence.complete(user,turn,reply.text(),metadata);
        } catch (RuntimeException e) {
            boolean interrupted = Thread.interrupted();
            try { persistence.fail(user,turn); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw e;
        }
    }

    public RequirementResponse requirement(CurrentUser user, Long id) {
        var transcript = persistence.transcript(user,id);
        var extracted = extraction.extract(transcript.userText(),transcript.knownTestType());
        if (extracted.testTypeCode() == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"시험유형을 명확히 입력해 주세요.",null);
        return persistence.saveRequirement(user,id,transcript,extracted);
    }

    /**
     * 그 시험유형의 DB 장비 중 최대 5대 (내 조직 장비 제외, 예약 가능부터). 정비 중·사용 불가는 후보 조회에서 이미 빠짐.
     * 적합 여부는 규칙 매칭이 판단하므로 여기서는 소개용 정보만 넘김. 조회가 실패해도 상담은 이어감
     */
    private List<kr.astra.equipment.dto.EquipmentCandidate> suggestEquipment(CurrentUser user, String testTypeCode) {
        if (testTypeCode == null) return List.of();
        try {
            return equipment.findCandidates(testTypeCode, null).stream()
                    .filter(e -> user.isAdmin() || !Objects.equals(e.institutionId(), user.organizationId()))
                    .sorted(Comparator.comparing((kr.astra.equipment.dto.EquipmentCandidate e) ->
                            e.availability() == kr.astra.equipment.entity.Availability.AVAILABLE ? 0 : 1)
                            .thenComparing(kr.astra.equipment.dto.EquipmentCandidate::id))
                    .limit(SUGGEST_LIMIT).toList();
        } catch (RuntimeException e) {
            log.warn("상담용 장비 후보 조회 실패, 장비 소개 없이 답변합니다: {}", e.toString());
            return List.of();
        }
    }

    private static Map<String,Object> brief(kr.astra.equipment.dto.EquipmentCandidate e) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("name", e.name());
        m.put("institution", e.institutionName());
        m.put("region", e.region());
        m.put("availability", e.availability());
        m.put("accessScope", e.accessScope());
        m.put("isDemoData", e.isMock());
        return m;
    }

    /** 조건 추출은 상담의 보조 정보라, AI 실패 시 직전 턴의 조건으로 상담을 이어갑니다 (전체 턴 실패 방지). */
    private Extracted extract(ChatPersistence.Transcript transcript) {
        try {
            var e = kr.astra.llm.client.LlmDeadline.within(java.time.Duration.ofSeconds(extractionTimeoutSeconds),
                    () -> extraction.extract(transcript.userText(),transcript.knownTestType()));
            return new Extracted(e.testTypeCode(),e.conditions(),e.metadata());
        } catch (BusinessException e) {
            if (!AiFallback.recoverable(e) || Thread.currentThread().isInterrupted()) throw e;
            log.warn("상담 조건 추출 실패, 이전 조건으로 답변을 이어갑니다: {}", e.getErrorCode());
            return new Extracted(transcript.knownTestType(),previousConditions(transcript),Map.of());
        }
    }
    private Object previousConditions(ChatPersistence.Transcript transcript) {
        for (var m : transcript.messages().reversed()) {
            if (m.role() != ChatRole.ASSISTANT || m.metadata() == null) continue;
            Object conditions = m.metadata().get("extractedConditions");
            if (conditions != null) return conditions;
        }
        return List.of();
    }
}
