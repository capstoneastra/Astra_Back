package kr.astra.equipment.dto;

import java.math.BigDecimal;
import kr.astra.equipment.entity.SpecSourceType;

/**
 * 장비 사양 한 줄 (equipment_specs). 단일값은 value, 범위는 min/max 에 들어 있습니다.
 *
 * @param specKey    단위 없는 snake_case (max_payload, frequency …). test_condition_rules.spec_key 와 같은 키
 * @param unit       단위 (kg, Hz, GHz …). 비교 전에 조건 규칙의 단위로 환산
 * @param sourceType REAL(기관 확인 사양) / MOCK(시연용)
 */
public record EquipmentSpecView(
        String specKey,
        String label,
        BigDecimal value,
        BigDecimal min,
        BigDecimal max,
        String valueText,
        String unit,
        SpecSourceType sourceType) {
}
