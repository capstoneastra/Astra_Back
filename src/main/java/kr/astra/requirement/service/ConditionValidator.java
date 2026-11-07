package kr.astra.requirement.service;

import java.math.BigDecimal;
import java.util.*;
import kr.astra.global.exception.*;
import kr.astra.requirement.dto.ConditionInput;
import kr.astra.requirement.entity.*;
import kr.astra.testtype.dto.ConditionRuleResponse;
import org.springframework.stereotype.Component;

@Component
public class ConditionValidator {
    public List<ConditionInput> validate(List<ConditionInput> inputs, List<ConditionRuleResponse> rules) {
        if (inputs == null || inputs.size() > 100) throw invalid();
        Map<String, ConditionRuleResponse> byKey = new HashMap<>();
        rules.forEach(r -> byKey.put(r.conditionKey(), r));
        Set<String> seen = new HashSet<>();
        List<ConditionInput> result = new ArrayList<>();
        for (ConditionInput c : inputs) {
            if (c == null || c.key() == null || !seen.add(c.key()) || !byKey.containsKey(c.key())) throw invalid();
            ConditionRuleResponse rule = byKey.get(c.key());
            if (c.status() == null || tooLong(c.valueText(), 300) || tooLong(c.note(), 300)
                    || (c.confidence() != null && (c.confidence().signum() < 0
                    || c.confidence().compareTo(BigDecimal.ONE) > 0))) throw invalid();
            boolean any = c.value() != null || c.min() != null || c.max() != null || (c.valueText() != null && !c.valueText().isBlank());
            if (c.status() == ConditionStatus.MISSING) {
                if (any) throw invalid();
                result.add(new ConditionInput(c.key(), null, null, null, null, rule.unit(), c.status(), c.confidence(), c.note()));
                continue;
            }
            if (!any) throw invalid();
            for (BigDecimal n : Arrays.asList(c.value(),c.min(),c.max()))
                if (n != null && (n.precision() > 30 || Math.abs((long)n.scale()) > 12)) throw invalid();
            boolean number = c.value() != null && c.min() == null && c.max() == null && c.valueText() == null;
            boolean range = c.value() == null && c.min() != null && c.max() != null
                    && c.min().compareTo(c.max()) <= 0 && c.valueText() == null;
            boolean text = c.value() == null && c.min() == null && c.max() == null
                    && c.valueText() != null && !c.valueText().isBlank();
            if (rule.valueType() == ConditionValueType.NUMBER && !number
                    || rule.valueType() == ConditionValueType.RANGE && !(range || number)
                    || rule.valueType() == ConditionValueType.TEXT && !text) throw invalid();
            try {
                if (text && !Objects.equals(c.unit(), rule.unit())) throw invalid();
                BigDecimal value = UnitConverter.convert(c.value(), c.unit(), rule.unit());
                BigDecimal min = UnitConverter.convert(c.min(), c.unit(), rule.unit());
                BigDecimal max = UnitConverter.convert(c.max(), c.unit(), rule.unit());
                // Negative temperature/voltage are meaningful; physical sizes, mass and frequency are not.
                if (Set.of("kg", "mm", "Hz", "GHz", "kN", "N", "kgf", "x", "samples/day", "%RH").contains(
                        rule.unit() == null ? "" : rule.unit())) {
                    for (BigDecimal n : Arrays.asList(value, min, max)) if (n != null && n.signum() < 0) throw invalid();
                }
                if ("%RH".equals(rule.unit()))
                    for (BigDecimal n : Arrays.asList(value, min, max))
                        if (n != null && n.compareTo(BigDecimal.valueOf(100)) > 0) throw invalid();
                result.add(new ConditionInput(c.key(), value, min, max, c.valueText(), rule.unit(), c.status(),
                        c.confidence(), c.note()));
            } catch (IllegalArgumentException e) { throw invalid(); }
        }
        return List.copyOf(result);
    }
    private boolean tooLong(String s, int max) { return s != null && s.length() > max; }
    private BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "조건 키·값·범위·단위·상태를 확인해 주세요.", null);
    }
}
