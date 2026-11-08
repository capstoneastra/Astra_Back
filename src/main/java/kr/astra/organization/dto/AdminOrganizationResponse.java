package kr.astra.organization.dto;

import java.time.OffsetDateTime;
import kr.astra.organization.entity.OrgType;
import kr.astra.organization.entity.Organization;

/** 관리자 조직 목록·등록·승인·수정 응답 (api-spec §12) */
public record AdminOrganizationResponse(Long id, OrgType orgType, String name, String parentName,
                                        String businessRegNo, String region, String address, String phone,
                                        String email, String homepageUrl, boolean isVerified,
                                        OffsetDateTime createdAt) {

    public static AdminOrganizationResponse from(Organization org) {
        return new AdminOrganizationResponse(org.getId(), org.getOrgType(), org.getName(), org.getParentName(),
                org.getBusinessRegNo(), org.getRegion(), org.getAddress(), org.getPhone(), org.getEmail(),
                org.getHomepageUrl(), org.isVerified(), org.getCreatedAt());
    }
}
