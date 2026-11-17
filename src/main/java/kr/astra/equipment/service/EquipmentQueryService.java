package kr.astra.equipment.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /** 여러 장비의 사양을 한 번에 (장비 id → 사양, 같은 키면 REAL 먼저). 사양이 없는 장비는 빈 목록 */
    Map<Long, List<EquipmentSpecView>> getSpecs(Collection<Long> equipmentIds);

    /** 매칭 결과 화면용 장비 요약을 한 번에. 없거나 비활성인 장비는 결과에서 빠짐 */
    Map<Long, EquipmentCandidate> findSummaries(Collection<Long> equipmentIds);

    /**
     * findCandidates 와 같은 조건의 후보 수. excludedInstitutionId 가 있으면 그 기관 장비는 셈에서 뺌
     * (자기 소속 조직 장비에는 의뢰할 수 없으므로 매칭과 같은 기준).
     */
    long countCandidates(String testTypeCode, String region, Long excludedInstitutionId);
}
