package kr.astra.llm.service;
import java.util.*;
import kr.astra.llm.client.LlmClient;
import kr.astra.llm.prompt.PromptLoader;
import kr.astra.matching.dto.MatchResponse;
import kr.astra.matching.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Describes already computed results. The model has no authority to change a check, score or rank. */
@Service @RequiredArgsConstructor
public class MatchExplanationService {
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final StructuredJson json;
    public record Input(Long equipmentId, MatchVerdict verdict, List<MatchResponse.Check> checks) {}
    public Map<Long,String> explain(List<Input> inputs) {
        if (inputs.isEmpty()) return Map.of();
        var keys=Map.of("type","array","items",Map.of("type","string"));
        var item=StructuredJson.object(Map.of("equipmentId",Map.of("type","integer"),
                "verdict",Map.of("type","string","enum",List.of("SUITABLE","PARTIAL","UNSUITABLE","UNKNOWN")),
                "metKeys",keys,"notMetKeys",keys,"unknownKeys",keys,"explanation",Map.of("type","string")));
        var reply=llm.complete(new LlmClient.Request(prompts.load("match-explanation"),json.write(inputs),
                StructuredJson.object(Map.of("results",Map.of("type","array","items",item))),"low"));
        var root=json.parse(reply.text()); json.fields(root,"results");
        var results=root.get("results");
        if (results == null || !results.isArray() || results.size() != inputs.size()) throw StructuredJson.invalid();
        Map<Long,Input> expected=new HashMap<>(); inputs.forEach(i -> expected.put(i.equipmentId(),i));
        Map<Long,String> explanations=new HashMap<>();
        for (var r:results) {
            json.fields(r,"equipmentId","verdict","metKeys","notMetKeys","unknownKeys","explanation");
            var idNode=r.get("equipmentId");
            if (idNode == null || !idNode.isIntegralNumber() || !idNode.canConvertToLong()) throw StructuredJson.invalid();
            long id=idNode.longValue();
            Input original=expected.get(id);
            if (original == null || explanations.containsKey(id)
                    || !original.verdict().name().equals(json.text(r,"verdict",true,20))) throw StructuredJson.invalid();
            String[] fields={"metKeys","notMetKeys","unknownKeys"};
            CheckResult[] verdicts={CheckResult.MET,CheckResult.NOT_MET,CheckResult.UNKNOWN};
            for (int n=0;n<fields.length;n++) {
                var array=r.get(fields[n]); if (array == null || !array.isArray()) throw StructuredJson.invalid();
                Set<String> actual=new HashSet<>();
                for (var key:array) if (!key.isString() || !actual.add(key.asString())) throw StructuredJson.invalid();
                CheckResult verdict=verdicts[n];
                Set<String> wanted=new HashSet<>();
                original.checks().stream().filter(c -> c.result() == verdict).forEach(c -> wanted.add(c.conditionKey()));
                if (!actual.equals(wanted)) throw StructuredJson.invalid();
            }
            explanations.put(id,json.text(r,"explanation",true,2000));
        }
        return Map.copyOf(explanations);
    }
}
