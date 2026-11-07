package kr.astra.equipment.dto;

/**
 * 매칭 후보 장비 요약 (EquipmentQueryService.findCandidates 결과)
 */
public record EquipmentCandidate(
        Long id,
        String externalId,
        String name,
        Long institutionId,
        String institutionName,
        String region,
        boolean isMock) {
}
