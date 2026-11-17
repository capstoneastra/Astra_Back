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
    private record Extracted(String testTypeCode, Object conditions, Map<String,Object> metadata) {}

    public ChatResponse.Exchange send(CurrentUser user, Long id, String content, Consumer<String> onText) {
        var turn = persistence.begin(user,id,content);
        try {
            var transcript = turn.transcript();
            var extracted = extract(transcript);
            if (Thread.currentThread().isInterrupted()) throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
            String prompt = prompts.load("consultation");
            String payload = json.write(Map.of("messages",transcript.messages().stream()
                    .map(m -> Map.of("role",m.role(),"content",m.content())).toList(),"conditions",extracted.conditions()));
            var request = new LlmClient.Request(prompt,payload,null,"low");
            var reply = onText == null ? llm.complete(request) : llm.stream(request,onText);
            if (Thread.currentThread().isInterrupted()) throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
            // 프롬프트·전체 대화(promptInput)·원응답은 매 턴 누적되어 메타데이터가 제곱으로 커지므로 저장하지 않음
            Map<String,Object> metadata = new LinkedHashMap<>(extracted.metadata());
            metadata.put("extractedConditions",extracted.conditions());
            metadata.put("suggestedTestType",extracted.testTypeCode());
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

    /** 조건 추출은 상담의 보조 정보라, AI 실패 시 직전 턴의 조건으로 상담을 이어갑니다 (전체 턴 실패 방지). */
    private Extracted extract(ChatPersistence.Transcript transcript) {
        try {
            var e = extraction.extract(transcript.userText(),transcript.knownTestType());
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
