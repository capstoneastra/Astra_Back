package kr.astra.requirement.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import kr.astra.requirement.dto.*;
import kr.astra.testtype.dto.ConditionRuleResponse;
import lombok.*;

@Getter
@Entity
@Table(name = "requirement_conditions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequirementCondition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "requirement_id", nullable = false)
    private TestRequirement requirement;
    @Column(nullable = false, length = 80) private String conditionKey;
    @Column(nullable = false, length = 100) private String label;
    private BigDecimal valueNum;
    private BigDecimal minNum;
    private BigDecimal maxNum;
    @Column(length = 300) private String valueText;
    @Column(length = 30) private String unit;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ConditionStatus status;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ConditionSource source;
    @Column(precision = 3, scale = 2) private BigDecimal confidence;
    @Column(length = 300) private String note;

    public RequirementCondition(TestRequirement requirement, ConditionRuleResponse rule, ConditionInput input,
            ConditionSource source) {
        this.requirement = requirement;
        this.conditionKey = rule.conditionKey();
        this.label = rule.label();
        update(input, source);
    }

    public void update(ConditionInput input, ConditionSource source) {
        valueNum = input.value(); minNum = input.min(); maxNum = input.max();
        valueText = input.valueText(); unit = input.unit(); status = input.status();
        confidence = source == ConditionSource.USER ? null : input.confidence();
        note = input.note(); this.source = source;
    }

    public RequirementResponse.ConditionResponse response() {
        return new RequirementResponse.ConditionResponse(conditionKey, label, valueNum, minNum, maxNum,
                valueText, unit, status, source, confidence, note);
    }
    public RequirementSnapshot.Condition snapshot() {
        return new RequirementSnapshot.Condition(conditionKey, label, normalized(valueNum), normalized(minNum),
                normalized(maxNum), valueText, unit, status);
    }
    private BigDecimal normalized(BigDecimal n) { return n == null ? null : n.stripTrailingZeros(); }
}
