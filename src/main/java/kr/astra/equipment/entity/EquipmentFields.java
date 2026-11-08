package kr.astra.equipment.entity;

import java.math.BigDecimal;

/**
 * 장비 기본정보·이용조건 입력값 묶음 (등록·수정 공용). null 인 필드는 바꾸지 않습니다.
 */
public record EquipmentFields(
        String name,
        String model,
        String manufacturer,
        String category,
        EquipmentType equipmentType,
        String region,
        String description,
        String imageUrl,
        AccessScope accessScope,
        String feeText,
        Integer feeAmount,
        FeeUnit feeUnit,
        String durationText,
        BigDecimal utilizationRate,
        Availability availability) {
}
