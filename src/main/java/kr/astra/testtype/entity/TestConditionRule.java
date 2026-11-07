package kr.astra.testtype.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import kr.astra.requirement.entity.ConditionComparator;
import kr.astra.requirement.entity.ConditionValueType;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * test_condition_rules: 시험유형별 조건 규칙 (읽기 전용).
 * 사용자가 입력할 조건 키 ↔ 비교할 장비 사양 키(spec_key) ↔ 판정 방식(comparator).
 * <p>
 * 테이블 주인은 BE-C(매칭·누락 분석). BE-A 는 GET /test-types/{code} 의 conditionRules 로 보여주기만 하므로
 * 이 엔티티는 읽기 전용입니다. 매칭에서는 {@code TestTypeService.getConditionRules(code)} 로 조회하세요.
 * 엔티티를 따로 만들지 말고 이 클래스를 같이 씁니다.
 */
@Getter
@Entity
@Table(name = "test_condition_rules")
@IdClass(TestConditionRule.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestConditionRule {

    @Id
    @Column(name = "test_type_code", nullable = false, length = 50)
    private String testTypeCode;

    @Id
    @Column(name = "condition_key", nullable = false, length = 80)
    private String conditionKey;

    @Column(nullable = false, length = 100)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 10)
    private ConditionValueType valueType;

    @Column(length = 30)
    private String unit;

    @Column(name = "is_required", nullable = false)
    private boolean required;

    @Column(name = "spec_key", length = 80)
    private String specKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConditionComparator comparator;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** 복합 키 (test_type_code, condition_key) */
    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String testTypeCode;
        private String conditionKey;
    }
}
