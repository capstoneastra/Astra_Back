package kr.astra.organization.dto;

import kr.astra.organization.entity.OrgType;

/** GET /institutions 항목 (승인된 기관·대학, 이름 가나다순). equipmentCount = 활성 장비 수 */
public record InstitutionSummaryResponse(Long id, OrgType orgType, String name, String parentName, String region,
                                         long equipmentCount) {

    public static InstitutionSummaryResponse of(InstitutionInfo info, long equipmentCount) {
        return new InstitutionSummaryResponse(info.id(), info.orgType(), info.name(), info.parentName(),
                info.region(), equipmentCount);
    }
}
