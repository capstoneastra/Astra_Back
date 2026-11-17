package kr.astra.llm.service;
import java.util.*;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.security.CurrentUser;
import kr.astra.llm.client.LlmClient;
import kr.astra.llm.prompt.PromptLoader;
import kr.astra.requirement.dto.AnalysisResponse;
import kr.astra.requirement.service.RequirementApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
@Slf4j @Service @RequiredArgsConstructor
public class RequirementAnalysisService {
    private final RequirementApplicationService requirements;
    private final LlmClient llm;
    private final StructuredJson json;
    private final PromptLoader prompts;
    /** 누락·미확인 판정은 규칙으로 확정하고, AI 는 기본 질문 문구만 다듬습니다. AI 가 실패하면 기본 질문으로 응답합니다. */
    public AnalysisResponse analyze(CurrentUser user, Long id) {
        var analysis = requirements.analyze(user,id);
        if (analysis.missing().isEmpty()) return analysis;
        Map<String,String> questions;
        try { questions = questions(analysis); }
        catch (BusinessException e) {
            if (!AiFallback.recoverable(e)) throw e;
            log.warn("조건 분석 질문 생성 실패, 기본 질문으로 응답합니다: {}", e.getErrorCode());
            return analysis;
        }
        var missing = analysis.missing().stream().map(m -> questions.containsKey(m.key())
                ? new AnalysisResponse.Missing(m.key(),m.label(),questions.get(m.key())) : m).toList();
        return new AnalysisResponse(id,analysis.testTypeCode(),analysis.conditions(),missing,analysis.unconfirmed(),analysis.readyForMatching());
    }

    /** 키별 질문. 모르는 키·중복·빈 문구는 버리고, 빠진 키는 기본 질문을 그대로 씁니다. */
    private Map<String,String> questions(AnalysisResponse analysis) {
        Set<String> wanted = new LinkedHashSet<>();
        analysis.missing().forEach(m -> wanted.add(m.key()));
        var item = StructuredJson.object(Map.of("key", Map.of("type","string","enum",List.copyOf(wanted)),
                "question",Map.of("type","string")));
        var reply = llm.complete(new LlmClient.Request(prompts.load("analysis"),json.write(analysis),
                StructuredJson.object(Map.of("questions", Map.of("type","array","items",item))), "medium"));
        var root = json.parse(reply.text()); json.fields(root,"questions");
        var questions = root.get("questions");
        if (questions == null || !questions.isArray()) throw StructuredJson.invalid("questions 배열 아님");
        Map<String,String> byKey = new HashMap<>();
        for (var q : questions) {
            if (!q.isObject() || !Set.of("key","question").containsAll(q.propertyNames())) continue;
            var key = q.get("key"); var question = q.get("question");
            if (key == null || !key.isString() || !wanted.contains(key.asString())) continue;
            if (question == null || !question.isString() || question.asString().isBlank() || question.asString().length() > 500) continue;
            byKey.putIfAbsent(key.asString(),question.asString());
        }
        return byKey;
    }
}
