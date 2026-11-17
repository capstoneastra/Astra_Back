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
import kr.astra.requirement.service.UnitConverter;
import kr.astra.testtype.dto.ConditionRuleResponse;
import kr.astra.testtype.service.TestTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Reusable by BE-A natural-language search; never writes requirements or equipment. */
@Slf4j @Service @RequiredArgsConstructor
public class ConditionExtractionService {
    /** 숫자 + 바로 뒤 단위 후보 (콤마 천 단위 구분은 미리 제거) */
    private static final Pattern NUMBER = Pattern.compile("(?<![0-9.])([-+]?[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)\\s*([^\\s0-9~,()\\[\\]/:;]*)");
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final StructuredJson json;
    private final TestTypeService testTypes;
    private final ConditionValidator validator;
    public record Extraction(String testTypeCode, List<ConditionInput> conditions, Map<String,Object> metadata) {}
    private enum Grounding { NONE, VERBATIM, CONVERTED }

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
            throw StructuredJson.invalid("규칙의 사양 키가 사양 사전에 없음");
        String payload = json.write(Map.of("input", input, "rules", rules, "specDefinitions", definitions,
                "aliases", testTypes.conditionAliases(code)));
        LlmClient.Reply reply = llm.complete(new LlmClient.Request(prompt, payload, schema, "medium"));
        JsonNode root = json.parse(reply.text());
        json.fields(root, "conditions");
        JsonNode items = root.get("conditions");
        if (items == null || !items.isArray() || items.size() > 100) throw StructuredJson.invalid("conditions 배열 아님");
        Map<String, ConditionRuleResponse> byKey = new HashMap<>();
        rules.forEach(r -> byKey.put(r.conditionKey(), r));
        // 항목 하나가 틀려도 전체를 502 로 만들지 않음: 그 항목만 버리거나 UNCONFIRMED 로 낮춤
        List<ConditionInput> checked = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode c : items) {
            try {
                ConditionInput item = item(c, input, byKey);
                if (keys.contains(item.key())) { log.warn("AI 추출 조건 중복 항목 제외 (key={})", item.key()); continue; }
                checked.add(validator.validate(List.of(item), rules).getFirst());
                keys.add(item.key());
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.VALIDATION_FAILED) log.warn("AI 추출 조건 항목 제외: 값·범위·단위 검증 실패");
                // AI_UPSTREAM_ERROR 는 StructuredJson.invalid 가 사유를 이미 기록함
            }
        }
        List<ConditionInput> all = new ArrayList<>(checked);
        rules.stream().filter(r -> r.isRequired() && !keys.contains(r.conditionKey())).forEach(r ->
                all.add(new ConditionInput(r.conditionKey(),null,null,null,null,r.unit(),ConditionStatus.MISSING,null,null)));
        // 프롬프트·전체 입력·원응답은 매 턴 누적되면 메타데이터가 커지므로 저장하지 않음 (프롬프트는 지문만)
        return new Extraction(code, List.copyOf(all), Map.of("model", reply.model(), "requestId", reply.requestId(),
                "inputTokens",reply.inputTokens(),"outputTokens",reply.outputTokens(),"promptHash",PromptLoader.fingerprint(prompt)));
    }

    private ConditionInput item(JsonNode c, String input, Map<String, ConditionRuleResponse> rules) {
        json.fields(c, "key","value","min","max","valueText","unit","status","confidence","note","evidence");
        String key = json.text(c,"key",true,80);
        ConditionRuleResponse rule = rules.get(key);
        if (rule == null) throw StructuredJson.invalid("모르는 조건 키");
        String evidence = json.text(c, "evidence", true, 1000);
        BigDecimal value = json.number(c,"value"), min = json.number(c,"min"), max = json.number(c,"max");
        String text = json.text(c,"valueText",false,300);
        String rawUnit = json.text(c,"unit",false,30);
        String unit = UnitConverter.canonical(rawUnit, rule.unit());
        ConditionStatus status;
        try { status = ConditionStatus.valueOf(json.text(c,"status",true,20)); }
        catch (IllegalArgumentException e) { throw StructuredJson.invalid("알 수 없는 상태"); }
        if (status == ConditionStatus.MISSING) throw StructuredJson.invalid("추출 항목에 MISSING 상태");
        BigDecimal confidence = json.number(c,"confidence");
        String note = json.text(c,"note",false,300);

        List<String> doubts = new ArrayList<>();
        boolean verbatim = input.contains(evidence);
        if (!verbatim) doubts.add("근거 문구를 원문에서 그대로 찾지 못했습니다.");
        String source = verbatim ? evidence : input; // 근거가 원문과 다르면 값은 원문 전체에서 확인
        boolean allConverted = true, anyNumber = false;
        for (BigDecimal number : Arrays.asList(value,min,max)) {
            if (number == null) continue;
            anyNumber = true;
            Grounding g = grounding(source, number, unit, rule.unit());
            if (g == Grounding.NONE) throw StructuredJson.invalid("원문에 없는 숫자");
            if (g == Grounding.VERBATIM) allConverted = false;
        }
        if (text != null && !source.contains(text)) doubts.add("값 표현을 원문에서 확인하지 못했습니다.");
        if (rawUnit != null && !(anyNumber && allConverted) && !hasUnit(source, rawUnit, unit))
            doubts.add("단위를 원문에서 확인하지 못했습니다.");
        if (confidence == null || confidence.compareTo(new BigDecimal("0.9")) < 0) status = ConditionStatus.UNCONFIRMED;
        if (!doubts.isEmpty()) {
            status = ConditionStatus.UNCONFIRMED;
            String joined = String.join(" ", doubts) + (note == null ? "" : " " + note);
            note = joined.length() > 300 ? joined.substring(0, 300) : joined;
        }
        return new ConditionInput(key, value, min, max, text, unit, status, confidence, note);
    }

    private String detect(String input) {
        var types = testTypes.findAllOrdered();
        List<String> codes = types.stream().map(t -> t.getCode()).toList();
        Map<String,Object> codeSchema = new LinkedHashMap<>();
        codeSchema.put("type", List.of("string","null"));
        List<Object> choices = new ArrayList<>(codes); choices.add(null);
        codeSchema.put("enum", choices);
        // 단순 분류라 낮은 추론 수준으로 충분 (상담 한 턴의 지연·비용을 줄임)
        var reply = llm.complete(new LlmClient.Request(prompts.load("test-type"),
                json.write(Map.of("input",input,"types",types.stream().map(t -> Map.of("code",t.getCode(),
                        "nameKo",t.getNameKo(),"keywords",t.getKeywords())).toList())),
                StructuredJson.object(Map.of("testTypeCode",codeSchema)), "low"));
        JsonNode root = json.parse(reply.text());
        json.fields(root,"testTypeCode");
        String code = json.text(root,"testTypeCode",false,50);
        if (code == null) return null;
        if (!codes.contains(code)) throw StructuredJson.invalid("모르는 시험유형 코드");
        return code;
    }

    /**
     * 숫자가 원문에 그대로 있거나(VERBATIM), 원문의 다른 단위 값을 환산한 값이면(CONVERTED, 예: 2.4GHz→2400000000Hz) 인정.
     * "2,400" 같은 천 단위 구분과 "영하 40도"(-40) 표현도 받아들입니다.
     */
    private Grounding grounding(String source, BigDecimal expected, String unit, String ruleUnit) {
        String normalized = source.replaceAll("(?<=\\d),(?=\\d{3}(?!\\d))", "");
        boolean belowZero = normalized.contains("영하") || normalized.contains("마이너스");
        Grounding found = Grounding.NONE;
        Matcher m = NUMBER.matcher(normalized);
        while (m.find()) {
            BigDecimal n;
            try { n = new BigDecimal(m.group(1)); } catch (NumberFormatException e) { continue; }
            List<BigDecimal> candidates = belowZero && n.signum() > 0 ? List.of(n, n.negate()) : List.of(n);
            String tokenUnit = unitPrefix(m.group(2), ruleUnit);
            for (BigDecimal candidate : candidates) {
                if (candidate.compareTo(expected) == 0) return Grounding.VERBATIM;
                if (tokenUnit != null && unit != null && UnitConverter.convertible(tokenUnit) && UnitConverter.convertible(unit)) {
                    try {
                        if (UnitConverter.convert(candidate, tokenUnit, unit).compareTo(expected) == 0) found = Grounding.CONVERTED;
                    } catch (IllegalArgumentException ignored) { /* 다른 차원의 단위 */ }
                }
            }
        }
        return found;
    }

    /** "kg이고"처럼 조사가 붙은 단위에서 가장 긴 알려진 단위 접두어를 찾음 */
    private String unitPrefix(String token, String ruleUnit) {
        for (int end = token.length(); end > 0; end--) {
            String canonical = UnitConverter.canonical(token.substring(0, end), ruleUnit);
            if (UnitConverter.convertible(canonical)) return canonical;
        }
        return null;
    }

    private boolean hasUnit(String source, String rawUnit, String unit) {
        if (source.contains(rawUnit)) return true;
        return UnitConverter.surfaces(unit).stream().anyMatch(source::contains);
    }
}
