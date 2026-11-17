package kr.astra.organization.dto;

import java.util.List;
import java.util.Map;
import kr.astra.equipment.entity.EquipmentType;
import kr.astra.organization.entity.OrgType;

/**
 * GET /institutions/{id}: 기관 정보 + 보유 장비 수.
 *
 * @param equipmentByType 장비 유형별 수 (장비가 있는 유형만)
 * @param testTypes       이 기관에서 할 수 있는 시험유형과 장비 수 (시험유형 display_order 순)
 */
public record InstitutionDetailResponse(Long id, OrgType orgType, String name, String parentName, String region,
                                        String address, String phone, String email, String homepageUrl,
                                        long equipmentCount,
                                        Map<EquipmentType, Long> equipmentByType, List<TestTypeCount> testTypes) {

    public record TestTypeCount(String code, String nameKo, long equipmentCount) {
    }
}
