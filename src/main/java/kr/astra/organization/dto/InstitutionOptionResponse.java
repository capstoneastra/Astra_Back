package kr.astra.organization.dto;

import kr.astra.organization.entity.OrgType;
import kr.astra.organization.entity.Organization;

/** 가입 화면의 기관·대학 선택 목록 (GET /auth/institutions). 로그인 전이라 id·유형·이름·지역만 공개 */
public record InstitutionOptionResponse(Long id, OrgType orgType, String name, String region) {

    public static InstitutionOptionResponse from(Organization org) {
        return new InstitutionOptionResponse(org.getId(), org.getOrgType(), org.getName(), org.getRegion());
    }
}
