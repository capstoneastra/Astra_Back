package kr.astra.organization.dto;

import kr.astra.organization.entity.OrgType;
import kr.astra.organization.entity.Organization;

/** 관리자 화면용 조직 정보 (예: GET /admin/users 의 organization). 승인 판단에 필요한 사업자번호·연락처 포함 */
public record OrganizationAdminResponse(Long id, OrgType orgType, String name, String businessRegNo, String region,
                                        String address, String phone, boolean isVerified) {

    public static OrganizationAdminResponse from(Organization org) {
        return new OrganizationAdminResponse(org.getId(), org.getOrgType(), org.getName(), org.getBusinessRegNo(),
                org.getRegion(), org.getAddress(), org.getPhone(), org.isVerified());
    }
}
