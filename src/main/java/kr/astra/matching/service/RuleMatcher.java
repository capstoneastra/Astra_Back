package kr.astra.matching.service;

import java.math.*;
import java.util.*;
import kr.astra.equipment.dto.*;
import kr.astra.equipment.entity.SpecSourceType;
import kr.astra.matching.dto.MatchResponse;
import kr.astra.matching.entity.*;
import kr.astra.requirement.dto.RequirementSnapshot;
import kr.astra.requirement.entity.*;
import kr.astra.requirement.service.UnitConverter;
import kr.astra.testtype.dto.ConditionRuleResponse;
import org.springframework.stereotype.Component;

@Component
public class RuleMatcher {
    public record Evaluation(BigDecimal score, MatchVerdict verdict, boolean usedMockData, List<MatchResponse.Check> checks) {}
    public Evaluation evaluate(List<ConditionRuleResponse> rules, List<RequirementSnapshot.Condition> conditions,
            List<EquipmentSpecView> specs) {
        Map<String, RequirementSnapshot.Condition> byKey = new HashMap<>();
        conditions.forEach(c -> byKey.put(c.key(), c));
        Map<String, EquipmentSpecView> chosen = new HashMap<>();
        specs.forEach(s -> chosen.merge(s.specKey(), s,
                (a, b) -> a.sourceType() == SpecSourceType.REAL ? a : b));
        List<MatchResponse.Check> checks = new ArrayList<>();
        boolean mock = false;
        for (ConditionRuleResponse rule : rules) {
            var c = byKey.get(rule.conditionKey());
            // Optional absent conditions do not affect the score; required absent conditions do.
            if (!rule.isRequired() && (c == null || c.status() == ConditionStatus.MISSING)) continue;
            var spec = rule.specKey() == null ? null : chosen.get(rule.specKey());
            if (spec != null && rule.comparator() != ConditionComparator.NONE)
                mock |= spec.sourceType() == SpecSourceType.MOCK;
            CheckResult result = compare(rule, c, spec);
            String note = rule.comparator() == ConditionComparator.NONE ? "자동 비교 대상이 아닙니다. 기관 확인이 필요합니다."
                    : result == CheckResult.UNKNOWN ? "조건 미확인, 사양 누락 또는 값·단위를 비교할 수 없습니다."
                    : rule.comparator().name() + ": 규칙 단위 " + rule.unit() + "로 환산하여 비교";
            if (spec != null) note += " (" + spec.sourceType() + ")";
            checks.add(new MatchResponse.Check(rule.conditionKey(),
                    c == null ? "미정" : display(c.value(), c.min(), c.max(), c.valueText(), c.unit()),
                    spec == null ? "미확인" : display(spec.value(), spec.min(), spec.max(), spec.valueText(), spec.unit()),
                    result, note));
        }
        long met = checks.stream().filter(c -> c.result() == CheckResult.MET).count();
        boolean notMet = checks.stream().anyMatch(c -> c.result() == CheckResult.NOT_MET);
        MatchVerdict verdict = checks.isEmpty() ? MatchVerdict.UNKNOWN
                : notMet ? MatchVerdict.UNSUITABLE : met == checks.size() ? MatchVerdict.SUITABLE : MatchVerdict.PARTIAL;
        BigDecimal score = checks.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(met * 100).divide(BigDecimal.valueOf(checks.size()), 2, RoundingMode.HALF_UP);
        return new Evaluation(score, verdict, mock, List.copyOf(checks));
    }

    private CheckResult compare(ConditionRuleResponse rule, RequirementSnapshot.Condition c, EquipmentSpecView s) {
        if (rule.comparator() == ConditionComparator.NONE || c == null || c.status() != ConditionStatus.CONFIRMED || s == null)
            return CheckResult.UNKNOWN;
        if (c.value() != null && (c.min() != null || c.max() != null)
                || s.value() != null && (s.min() != null || s.max() != null)) return CheckResult.UNKNOWN;
        try {
            BigDecimal low = UnitConverter.convert(c.value() != null ? c.value() : c.min(), c.unit(), rule.unit());
            BigDecimal high = UnitConverter.convert(c.value() != null ? c.value() : c.max(), c.unit(), rule.unit());
            if (low == null || high == null || low.compareTo(high) > 0) return CheckResult.UNKNOWN;
            boolean nonnegative = Set.of("kg","mm","Hz","GHz","kN","N","kgf","x","samples/day","%RH")
                    .contains(rule.unit() == null ? "" : rule.unit());
            if (nonnegative && (low.signum() < 0 || high.signum() < 0)) return CheckResult.UNKNOWN;
            boolean met;
            switch (rule.comparator()) {
                case RANGE_WITHIN -> {
                    BigDecimal min = UnitConverter.convert(s.min(), s.unit(), rule.unit());
                    BigDecimal max = UnitConverter.convert(s.max(), s.unit(), rule.unit());
                    if (min == null || max == null || min.compareTo(max) > 0) return CheckResult.UNKNOWN;
                    if (nonnegative && (min.signum() < 0 || max.signum() < 0)) return CheckResult.UNKNOWN;
                    met = low.compareTo(min) >= 0 && high.compareTo(max) <= 0;
                }
                case MAX_LIMIT, MIN_LIMIT -> {
                    BigDecimal value = UnitConverter.convert(s.value(), s.unit(), rule.unit());
                    if (value == null) return CheckResult.UNKNOWN;
                    if (nonnegative && value.signum() < 0) return CheckResult.UNKNOWN;
                    met = rule.comparator() == ConditionComparator.MAX_LIMIT
                            ? high.compareTo(value) <= 0 : value.compareTo(low) <= 0;
                }
                default -> { return CheckResult.UNKNOWN; }
            }
            return met ? CheckResult.MET : CheckResult.NOT_MET;
        } catch (IllegalArgumentException e) { return CheckResult.UNKNOWN; }
    }

    private String display(BigDecimal value, BigDecimal min, BigDecimal max, String text, String unit) {
        String body = value != null ? value.toPlainString()
                : min != null && max != null ? min.toPlainString() + "~" + max.toPlainString()
                : text != null ? text : "미정";
        String result = body + (unit == null ? "" : " " + unit);
        return result.length() > 200 ? result.substring(0, 197) + "..." : result;
    }
}
