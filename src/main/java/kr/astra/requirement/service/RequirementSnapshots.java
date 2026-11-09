package kr.astra.requirement.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Objects;
import kr.astra.requirement.dto.RequirementSnapshot;

/** PostgreSQL JSONB may change decimal scales; compare values, not their lexical representation. */
public final class RequirementSnapshots {
    private RequirementSnapshots() {}
    public static boolean same(RequirementSnapshot left, RequirementSnapshot right) {
        return Objects.equals(normalize(left), normalize(right));
    }
    private static RequirementSnapshot normalize(RequirementSnapshot s) {
        if (s == null) return null;
        var conditions = s.conditions().stream().map(c -> new RequirementSnapshot.Condition(c.key(), c.label(),
                number(c.value()), number(c.min()), number(c.max()), c.valueText(), c.unit(), c.status()))
                .sorted(Comparator.comparing(RequirementSnapshot.Condition::key)).toList();
        return new RequirementSnapshot(s.requirementId(), s.testTypeCode(), s.title(), s.purpose(), s.sampleName(),
                s.sampleQuantity(), conditions);
    }
    private static BigDecimal number(BigDecimal value) { return value == null ? null : value.stripTrailingZeros(); }
}
