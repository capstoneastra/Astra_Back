package kr.astra.equipment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * equipment_test_types: 장비가 할 수 있는 시험유형 + 시험 정보 안내용 예상 비용·기간 (기능 4).
 * 시험유형은 code 로만 들고 있습니다 (시험유형 정보는 TestTypeService 로 조회).
 */
@Getter
@Entity
@Table(name = "equipment_test_types")
@IdClass(EquipmentTestType.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentTestType {

    @Id
    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Id
    @Column(name = "test_type_code", nullable = false, length = 50)
    private String testTypeCode;

    @Column(name = "est_cost_min")
    private Integer estCostMin;

    @Column(name = "est_cost_max")
    private Integer estCostMax;

    @Column(name = "est_duration_days")
    private Integer estDurationDays;

    @Column(length = 300)
    private String note;

    /** 복합 키 (equipment_id, test_type_code) */
    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long equipmentId;
        private String testTypeCode;
    }
}
