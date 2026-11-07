package kr.astra.equipment.dto;

import kr.astra.equipment.entity.AccessScope;

/**
 * 시험유형 하나에 대한 기관별 집계 (GET /test-types/{code} 의 institutions).
 *
 * @param estCostMin      그 기관 장비들의 예상 비용 최솟값 (없으면 null)
 * @param estCostMax      예상 비용 최댓값
 * @param estDurationDays 가장 짧은 예상 소요일
 * @param accessScope     하나라도 외부 이용 가능이면 EXTERNAL_ALLOWED, 모두 내부 전용이면 INTERNAL_ONLY, 그 외 UNKNOWN
 */
public record InstitutionTestTypeStat(
        Long institutionId,
        long equipmentCount,
        Integer estCostMin,
        Integer estCostMax,
        Integer estDurationDays,
        AccessScope accessScope) {
}
