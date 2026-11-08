package kr.astra.admin.service;

import kr.astra.admin.dto.AdminRequests.CreateOrganizationRequest;
import kr.astra.admin.dto.AdminRequests.UpdateOrganizationRequest;
import kr.astra.global.response.PageResponse;
import kr.astra.global.security.AccessGuard;
import kr.astra.global.security.CurrentUser;
import kr.astra.organization.dto.AdminOrganizationResponse;
import kr.astra.organization.entity.OrgType;
import kr.astra.organization.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 조직 관리 (api-spec §12): 목록, 기관 직접 등록, 승인, 수정 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminOrganizationService {

    private final OrganizationService organizationService;

    public PageResponse<AdminOrganizationResponse> list(CurrentUser admin, OrgType orgType, Boolean verified,
                                                        Pageable pageable) {
        AccessGuard.requireAdmin(admin);
        return PageResponse.from(organizationService.searchForAdmin(orgType, verified, pageable));
    }

    @Transactional
    public AdminOrganizationResponse create(CurrentUser admin, CreateOrganizationRequest r) {
        AccessGuard.requireAdmin(admin);
        return organizationService.createInstitutionByAdmin(r.name(), r.parentName(), r.region(), r.address(),
                r.phone(), r.email(), r.homepageUrl());
    }

    @Transactional
    public AdminOrganizationResponse verify(CurrentUser admin, Long organizationId) {
        AccessGuard.requireAdmin(admin);
        return organizationService.verifyByAdmin(organizationId);
    }

    @Transactional
    public AdminOrganizationResponse update(CurrentUser admin, Long organizationId, UpdateOrganizationRequest r) {
        AccessGuard.requireAdmin(admin);
        return organizationService.updateByAdmin(organizationId, r.name(), r.parentName(), r.businessRegNo(),
                r.region(), r.address(), r.phone(), r.email(), r.homepageUrl());
    }
}
