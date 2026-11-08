package kr.astra.equipment.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.astra.requirement.dto.ExtractedQuery;
import kr.astra.requirement.entity.ConditionValueType;
import kr.astra.testtype.dto.ConditionRuleResponse;
import kr.astra.testtype.entity.TestType;
import kr.astra.testtype.service.TestTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 키워드·숫자 기반 문장 해석 (GET /search 의 임시 해석기).
 * BE-C 의 {@code ConditionExtractor}(AI) 구현이 생기면 SearchService 가 그쪽을 쓰고 이 클래스는 쓰지 않습니다.
 * 일부러 ConditionExtractor 를 구현하지 않습니다 (구현 빈이 둘이 되어 충돌하지 않도록).
 * <ul>
 *   <li>시험유형: 문장에 시험유형 이름이나 키워드(test_types.keywords)가 들어 있으면 해당 (예: "진동" → VIBRATION)</li>
 *   <li>범위 "20~2000Hz": 찾은 시험유형의 조건 규칙 중 단위가 같은 RANGE 규칙 (frequency)</li>
 *   <li>값 "15kg": 단위가 같은 NUMBER 규칙 중 아직 안 쓴 첫 규칙 (sample_mass)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class KeywordConditionExtractor {

    private static final String NUMBER = "(\\d+(?:\\.\\d+)?)";
    private static final String UNIT = "([A-Za-z°℃%]+)";
    private static final Pattern RANGE = Pattern.compile(NUMBER + "\\s*" + UNIT + "?\\s*(?:~|∼|-|–|에서)\\s*"
            + NUMBER + "\\s*" + UNIT);
    private static final Pattern VALUE = Pattern.compile(NUMBER + "\\s*" + UNIT);

    private final TestTypeService testTypeService;

    public ExtractedQuery extract(String text) {
        if (text == null || text.isBlank()) {
            return ExtractedQuery.empty();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> codes = new ArrayList<>();
        for (TestType t : testTypeService.findAllOrdered()) {
            boolean byName = lower.contains(t.getNameKo().toLowerCase(Locale.ROOT));
            boolean byKeyword = t.getKeywords().stream().anyMatch(k -> lower.contains(k.toLowerCase(Locale.ROOT)));
            if (byName || byKeyword) {
                codes.add(t.getCode());
            }
        }
        if (codes.isEmpty()) {
            return new ExtractedQuery(List.of(), List.of());
        }

        // 찾은 시험유형들의 조건 규칙 (같은 키는 한 번만, 표시 순서)
        Map<String, ConditionRuleResponse> rules = new LinkedHashMap<>();
        codes.forEach(code -> testTypeService.getConditionRules(code)
                .forEach(r -> rules.putIfAbsent(r.conditionKey(), r)));
        Set<String> used = new HashSet<>();
        List<ExtractedQuery.Condition> conditions = new ArrayList<>();

        StringBuilder rest = new StringBuilder();
        Matcher range = RANGE.matcher(text);
        int last = 0;
        while (range.find()) {
            String unit = range.group(4);
            findRule(rules, used, unit, ConditionValueType.RANGE).ifPresent(r -> {
                used.add(r.conditionKey());
                conditions.add(new ExtractedQuery.Condition(r.conditionKey(), null, new BigDecimal(range.group(1)),
                        new BigDecimal(range.group(3)), null, r.unit()));
            });
            rest.append(text, last, range.start()).append(' ');
            last = range.end();
        }
        rest.append(text.substring(last));

        Matcher value = VALUE.matcher(rest);
        while (value.find()) {
            String unit = value.group(2);
            findRule(rules, used, unit, ConditionValueType.NUMBER).ifPresent(r -> {
                used.add(r.conditionKey());
                conditions.add(new ExtractedQuery.Condition(r.conditionKey(), new BigDecimal(value.group(1)), null,
                        null, null, r.unit()));
            });
        }
        // 조건 규칙의 표시 순서대로 (예: sample_mass → frequency)
        List<String> order = new ArrayList<>(rules.keySet());
        conditions.sort(java.util.Comparator.comparingInt(c -> order.indexOf(c.key())));
        return new ExtractedQuery(codes, conditions);
    }

    private static java.util.Optional<ConditionRuleResponse> findRule(Map<String, ConditionRuleResponse> rules,
                                                                      Set<String> used, String unit,
                                                                      ConditionValueType type) {
        return rules.values().stream()
                .filter(r -> !used.contains(r.conditionKey()))
                .filter(r -> r.valueType() == type)
                .filter(r -> r.unit() != null && r.unit().equalsIgnoreCase(unit))
                .findFirst();
    }
}
