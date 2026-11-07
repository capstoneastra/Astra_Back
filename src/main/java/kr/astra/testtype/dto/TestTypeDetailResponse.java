package kr.astra.testtype.dto;

import java.util.List;
import kr.astra.equipment.entity.AccessScope;

/**
 * GET /test-types/{code}: 시험 정보 안내 (기능 4) + 조건 입력폼·AI 누락 분석·매칭이 쓰는 conditionRules.
 * institutions 는 장비 수가 많은 기관부터 (같으면 이름 가나다순).
 */
public record TestTypeDetailResponse(String code, String nameKo, String description,
                                     List<ConditionRuleResponse> conditionRules, List<Institution> institutions) {

    /**
     * 이 시험을 할 수 있는 기관
     *
     * @param estCostMin      그 기관 장비들의 예상 비용 최솟값(원), 정보 없으면 null
     * @param estCostMax      예상 비용 최댓값(원)
     * @param estDurationDays 가장 짧은 예상 소요일
     * @param accessScope     하나라도 외부 이용 가능이면 EXTERNAL_ALLOWED, 모두 내부 전용이면 INTERNAL_ONLY, 그 외 UNKNOWN
     */
    public record Institution(Long id, String name, String region, long equipmentCount, Integer estCostMin,
                              Integer estCostMax, Integer estDurationDays, AccessScope accessScope) {
    }
}
