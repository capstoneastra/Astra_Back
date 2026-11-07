package kr.astra.equipment.service;

import java.util.List;
import kr.astra.equipment.dto.EquipmentCandidate;
import kr.astra.equipment.dto.EquipmentSpecView;

/**
 * [BE-A 제공 → BE-C 사용] 매칭용 장비 조회. 구현: BE-A (team-guide.md §4 내부 인터페이스)
 */
public interface EquipmentQueryService {

    /**
     * 해당 시험유형을 할 수 있는 활성 장비 목록.
     *
     * @param testTypeCode test_types.code (예: VIBRATION)
     * @param region       지역 필터, null 이면 전체
     */
    List<EquipmentCandidate> findCandidates(String testTypeCode, String region);

    /**
     * 장비 사양 전체 (REAL·MOCK 모두). 매칭은 같은 spec_key 에 REAL 이 있으면 REAL 을 우선 사용합니다.
     */
    List<EquipmentSpecView> getSpecs(Long equipmentId);
}
