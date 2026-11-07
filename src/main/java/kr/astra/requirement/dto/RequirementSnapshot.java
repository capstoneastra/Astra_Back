package kr.astra.requirement.dto;

import java.math.BigDecimal;
import java.util.List;
import kr.astra.requirement.entity.ConditionStatus;

/**
 * 시험조건 스냅샷 (test_requirements + requirement_conditions)
 */
public record RequirementSnapshot(
        Long requirementId,
        String testTypeCode,
        String title,
        String purpose,
        String sampleName,
        Integer sampleQuantity,
        List<Condition> conditions) {

    /**
     * @param key    test_condition_rules.condition_key (sample_mass, fixture_mass, frequency …)
     * @param status CONFIRMED / UNCONFIRMED / MISSING
     */
    public record Condition(
            String key,
            String label,
            BigDecimal value,
            BigDecimal min,
            BigDecimal max,
            String valueText,
            String unit,
            ConditionStatus status) {
    }
}
