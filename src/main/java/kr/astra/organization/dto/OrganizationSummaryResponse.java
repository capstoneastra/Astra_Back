package kr.astra.organization.dto;

import kr.astra.organization.entity.OrgType;
import kr.astra.organization.entity.Organization;

/** 다른 응답 안에 넣는 조직 요약 (예: GET /users/me 의 organization) */
public record OrganizationSummaryResponse(Long id, OrgType orgType, String name, String region, boolean isVerified) {

    public static OrganizationSummaryResponse from(Organization org) {
        return new OrganizationSummaryResponse(org.getId(), org.getOrgType(), org.getName(), org.getRegion(),
                org.isVerified());
    }
}
