package kr.astra.equipment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * equipment_specs: 장비 사양 한 줄. 실제 사양(REAL)과 시연용(MOCK)을 source_type 으로 구분합니다.
 * 단일값은 value_num, 범위는 min_num/max_num, 숫자로 못 쓰는 값은 value_text.
 */
@Getter
@Entity
@Table(name = "equipment_specs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentSpec {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "spec_key", nullable = false, length = 80)
    private String specKey;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(name = "value_num")
    private BigDecimal valueNum;

    @Column(name = "min_num")
    private BigDecimal minNum;

    @Column(name = "max_num")
    private BigDecimal maxNum;

    @Column(name = "value_text", length = 300)
    private String valueText;

    @Column(length = 30)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 10)
    private SpecSourceType sourceType;

    /** CHECK 제약이 없는 컬럼이라 문자열 그대로 (현재 seed 는 NOT_VERIFIED) */
    @Column(name = "verification_status", nullable = false, length = 30)
    private String verificationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_scope", nullable = false, length = 20)
    private UsageScope usageScope;

    /** 기관 담당자가 입력한 실제 사양 (REAL, 기관 확인, 일반 사용) */
    public static EquipmentSpec real(Long equipmentId, String specKey, String label, BigDecimal valueNum,
                                     BigDecimal minNum, BigDecimal maxNum, String valueText, String unit) {
        EquipmentSpec s = new EquipmentSpec();
        s.equipmentId = equipmentId;
        s.specKey = specKey;
        s.label = label;
        s.valueNum = valueNum;
        s.minNum = minNum;
        s.maxNum = maxNum;
        s.valueText = valueText;
        s.unit = unit;
        s.sourceType = SpecSourceType.REAL;
        s.verificationStatus = "VERIFIED";
        s.usageScope = UsageScope.GENERAL;
        return s;
    }
}
