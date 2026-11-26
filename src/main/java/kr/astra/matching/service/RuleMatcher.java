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
        return evaluate(rules, conditions, specs, null);
    }
    public Evaluation evaluate(List<ConditionRuleResponse> rules, List<RequirementSnapshot.Condition> conditions,
            List<EquipmentSpecView> specs, Boolean accredited) {
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
            boolean accreditation = "accredited_required".equals(rule.conditionKey());
            CheckResult result = accreditation ? accreditation(c, accredited) : compare(rule, c, spec);
            String note = accreditation ? "장비·시험유형의 기관 확인 공인 여부와 한국 날짜 기준 인정 유효기간으로 판정"
                    : rule.comparator() == ConditionComparator.NONE ? "자동 비교 대상이 아닙니다. 기관 확인이 필요합니다."
                    : result == CheckResult.UNKNOWN ? unknownReason(c, spec)
                    : switch (rule.comparator()) {
                        case RANGE_WITHIN -> "요청 범위가 장비 범위 안에 드는지 비교";
                        case MAX_LIMIT -> "요청 값이 장비 최대치 이하인지 비교";
                        case MIN_LIMIT -> "장비 사양이 요청 값 이하(더 정밀)인지 비교";
                        default -> "자동 비교";
                    } + " (" + rule.unit() + " 기준)";
            if (spec != null) note += " (" + spec.sourceType() + ")";
            checks.add(new MatchResponse.Check(rule.conditionKey(),
                    c == null ? "미정" : display(c.value(), c.min(), c.max(), c.valueText(), c.unit()),
                    accreditation ? accredited == null ? "미확인" : accredited ? "공인 가능" : "불가 또는 인정 만료"
                            : spec == null ? "미확인" : display(spec.value(), spec.min(), spec.max(), spec.valueText(), spec.unit()),
                    result, note, rule.label()));
        }
        long met = checks.stream().filter(c -> c.result() == CheckResult.MET).count();
        boolean notMet = checks.stream().anyMatch(c -> c.result() == CheckResult.NOT_MET);
        MatchVerdict verdict = checks.isEmpty() ? MatchVerdict.UNKNOWN
                : notMet ? MatchVerdict.UNSUITABLE : met == checks.size() ? MatchVerdict.SUITABLE : MatchVerdict.PARTIAL;
        BigDecimal score = checks.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(met * 100).divide(BigDecimal.valueOf(checks.size()), 2, RoundingMode.HALF_UP);
        return new Evaluation(score, verdict, mock, List.copyOf(checks));
    }

    /** 확인 필요(UNKNOWN)가 된 이유를 경우별로 */
    private static String unknownReason(RequirementSnapshot.Condition c, EquipmentSpecView spec) {
        if (c == null || c.status() == ConditionStatus.MISSING) return "의뢰 조건에 값이 없어 비교하지 못했습니다. 조건을 입력해 주세요.";
        if (c.status() != ConditionStatus.CONFIRMED) return "조건이 아직 확정되지 않아 비교하지 않았습니다. 조건을 확인해 주세요.";
        if (spec == null) return "장비에 이 사양 정보가 없어 기관 확인이 필요합니다.";
        return "조건과 장비 사양의 값·단위를 비교할 수 없어 기관 확인이 필요합니다.";
    }

    private CheckResult accreditation(RequirementSnapshot.Condition c, Boolean accredited) {
        if (c == null || c.status() != ConditionStatus.CONFIRMED) return CheckResult.UNKNOWN;
        if ("불필요".equals(c.valueText())) return CheckResult.MET;
        if (!"필요".equals(c.valueText()) || accredited == null) return CheckResult.UNKNOWN;
        return accredited ? CheckResult.MET : CheckResult.NOT_MET;
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
