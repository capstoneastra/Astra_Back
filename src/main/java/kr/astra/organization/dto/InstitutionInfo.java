package kr.astra.organization.dto;

import kr.astra.organization.entity.OrgType;
import kr.astra.organization.entity.Organization;

/** 기관 기본 정보 (GET /institutions·/institutions/{id} 를 만들 때 사용) */
public record InstitutionInfo(Long id, OrgType orgType, String name, String parentName, String region, String address, String phone,
                              String email, String homepageUrl) {

    public static InstitutionInfo from(Organization org) {
        return new InstitutionInfo(org.getId(), org.getOrgType(), org.getName(), org.getParentName(), org.getRegion(),
                org.getAddress(), org.getPhone(), org.getEmail(), org.getHomepageUrl());
    }
}
