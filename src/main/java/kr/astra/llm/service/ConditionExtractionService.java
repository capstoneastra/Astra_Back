package kr.astra.llm.service;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import kr.astra.global.exception.*;
import kr.astra.llm.client.LlmClient;
import kr.astra.llm.prompt.PromptLoader;
import kr.astra.requirement.dto.ConditionInput;
import kr.astra.requirement.entity.ConditionStatus;
import kr.astra.requirement.service.ConditionValidator;
import kr.astra.testtype.service.TestTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Reusable by BE-A natural-language search; never writes requirements or equipment. */
@Service @RequiredArgsConstructor
public class ConditionExtractionService {
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final StructuredJson json;
    private final TestTypeService testTypes;
    private final ConditionValidator validator;
    public record Extraction(String testTypeCode, List<ConditionInput> conditions, Map<String,Object> metadata) {}

    public Extraction extract(String input, String knownType) {
        if (input == null || input.isBlank() || input.length() > 50000)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        String code = knownType == null ? detect(input) : knownType;
        if (code == null) return new Extraction(null, List.of(), Map.of("needsTestType", true));
        var rules = testTypes.getConditionRules(code);
        if (rules.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "시험유형을 먼저 지정해 주세요.", null);
        Map<String,Object> props = new LinkedHashMap<>();
        props.put("key", Map.of("type", "string", "enum", rules.stream().map(r -> r.conditionKey()).toList()));
        for (String key : List.of("value","min","max","confidence")) props.put(key, StructuredJson.nullable("number"));
        for (String key : List.of("valueText","unit","note")) props.put(key, StructuredJson.nullable("string"));
        props.put("status", Map.of("type", "string", "enum", List.of("CONFIRMED","UNCONFIRMED")));
        props.put("evidence", Map.of("type", "string"));
        var schema = StructuredJson.object(Map.of("conditions", Map.of("type","array","items",StructuredJson.object(props))));
        String prompt = prompts.load("condition-extraction");
        var definitions = testTypes.specDefinitions();
        Set<String> definedKeys = new HashSet<>();
        definitions.forEach(d -> definedKeys.add(d.specKey()));
        if (rules.stream().anyMatch(r -> r.specKey() != null && !definedKeys.contains(r.specKey())))
            throw StructuredJson.invalid();
        String payload = json.write(Map.of("input", input, "rules", rules, "specDefinitions", definitions,
                "aliases", testTypes.conditionAliases(code)));
        LlmClient.Reply reply = llm.complete(new LlmClient.Request(prompt, payload, schema, "medium"));
        JsonNode root = json.parse(reply.text());
        json.fields(root, "conditions");
        JsonNode items = root.get("conditions");
        if (items == null || !items.isArray() || items.size() > rules.size()) throw StructuredJson.invalid();
        List<ConditionInput> conditions = new ArrayList<>();
        for (JsonNode c : items) {
            json.fields(c, "key","value","min","max","valueText","unit","status","confidence","note","evidence");
            String evidence = json.text(c, "evidence", true, 1000);
            if (!input.contains(evidence)) throw StructuredJson.invalid();
            BigDecimal value = json.number(c,"value"), min = json.number(c,"min"), max = json.number(c,"max");
            for (BigDecimal number : Arrays.asList(value,min,max))
                if (number != null && !containsNumber(evidence, number)) throw StructuredJson.invalid();
            String text = json.text(c,"valueText",false,300);
            if (text != null && !evidence.contains(text)) throw StructuredJson.invalid();
            String unit = json.text(c,"unit",false,30);
            if (unit != null && !evidence.contains(unit)) throw StructuredJson.invalid();
            ConditionStatus status;
            try { status = ConditionStatus.valueOf(json.text(c,"status",true,20)); }
            catch (IllegalArgumentException e) { throw StructuredJson.invalid(); }
            if (status == ConditionStatus.MISSING) throw StructuredJson.invalid();
            BigDecimal confidence = json.number(c,"confidence");
            if (confidence == null || confidence.compareTo(new BigDecimal("0.9")) < 0) status = ConditionStatus.UNCONFIRMED;
            conditions.add(new ConditionInput(json.text(c,"key",true,80), value,min,max,text,unit,status,confidence,
                    json.text(c,"note",false,300)));
        }
        List<ConditionInput> checked;
        try { checked = validator.validate(conditions, rules); }
        catch (BusinessException e) { throw StructuredJson.invalid(); }
        List<ConditionInput> all = new ArrayList<>(checked);
        Set<String> keys = new HashSet<>();
        checked.forEach(c -> keys.add(c.key()));
        rules.stream().filter(r -> r.isRequired() && !keys.contains(r.conditionKey())).forEach(r ->
                all.add(new ConditionInput(r.conditionKey(),null,null,null,null,r.unit(),ConditionStatus.MISSING,null,null)));
        return new Extraction(code, List.copyOf(all), Map.of("model", reply.model(), "requestId", reply.requestId(),
                "inputTokens",reply.inputTokens(),"outputTokens",reply.outputTokens(),"prompt",prompt,
                "promptInput",payload,"rawResponse",reply.text()));
    }

    private String detect(String input) {
        var types = testTypes.findAllOrdered();
        List<String> codes = types.stream().map(t -> t.getCode()).toList();
        Map<String,Object> codeSchema = new LinkedHashMap<>();
        codeSchema.put("type", List.of("string","null"));
        List<Object> choices = new ArrayList<>(codes); choices.add(null);
        codeSchema.put("enum", choices);
        var reply = llm.complete(new LlmClient.Request(prompts.load("test-type"),
                json.write(Map.of("input",input,"types",types.stream().map(t -> Map.of("code",t.getCode(),
                        "nameKo",t.getNameKo(),"keywords",t.getKeywords())).toList())),
                StructuredJson.object(Map.of("testTypeCode",codeSchema)), "medium"));
        JsonNode root = json.parse(reply.text());
        json.fields(root,"testTypeCode");
        String code = json.text(root,"testTypeCode",false,50);
        if (code == null) return null;
        if (!codes.contains(code)) throw StructuredJson.invalid();
        return code;
    }

    private boolean containsNumber(String evidence, BigDecimal expected) {
        Matcher matcher = Pattern.compile("(?<![0-9.])[-+]?(?:[0-9]+(?:\\.[0-9]+)?)(?:[eE][-+]?[0-9]+)?").matcher(evidence);
        while (matcher.find()) if (new BigDecimal(matcher.group()).compareTo(expected) == 0) return true;
        return false;
    }
}
