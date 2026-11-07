package kr.astra.llm.service;
import java.util.*;
import kr.astra.global.security.CurrentUser;
import kr.astra.llm.client.LlmClient;
import kr.astra.llm.prompt.PromptLoader;
import kr.astra.requirement.dto.AnalysisResponse;
import kr.astra.requirement.service.RequirementApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class RequirementAnalysisService {
    private final RequirementApplicationService requirements;
    private final LlmClient llm;
    private final StructuredJson json;
    private final PromptLoader prompts;
    public AnalysisResponse analyze(CurrentUser user, Long id) {
        var analysis = requirements.analyze(user,id);
        if (analysis.missing().isEmpty()) return analysis;
        var item = StructuredJson.object(Map.of("key", Map.of("type","string","enum",
                analysis.missing().stream().map(AnalysisResponse.Missing::key).toList()), "question",Map.of("type","string")));
        var reply = llm.complete(new LlmClient.Request(prompts.load("analysis"),json.write(analysis),
                StructuredJson.object(Map.of("questions", Map.of("type","array","items",item))), "medium"));
        var root = json.parse(reply.text()); json.fields(root,"questions");
        var questions = root.get("questions");
        if (questions == null || !questions.isArray() || questions.size() != analysis.missing().size()) throw StructuredJson.invalid();
        Map<String,String> byKey = new HashMap<>();
        for (var q : questions) {
            json.fields(q,"key","question");
            if (byKey.put(json.text(q,"key",true,80),json.text(q,"question",true,500)) != null) throw StructuredJson.invalid();
        }
        var missing = analysis.missing().stream().map(m -> {
            if (!byKey.containsKey(m.key())) throw StructuredJson.invalid();
            return new AnalysisResponse.Missing(m.key(),m.label(),byKey.get(m.key()));
        }).toList();
        return new AnalysisResponse(id,analysis.testTypeCode(),analysis.conditions(),missing,analysis.unconfirmed(),analysis.readyForMatching());
    }
}
