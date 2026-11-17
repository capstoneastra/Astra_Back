package kr.astra.global.security;

import kr.astra.user.entity.UserRole;

/**
 * 로그인한 사용자 정보. JWT 필터(BE-A)가 SecurityContext 에 principal 로 넣고,
 * 컨트롤러에서는 {@code @AuthenticationPrincipal CurrentUser user} 로 받습니다.
 *
 * @param userId         users.id
 * @param role           COMPANY_USER / INSTITUTION_MANAGER / ADMIN
 * @param organizationId users.organization_id (소속 조직. 장비·의뢰 소유권 체크에 사용)
 */
public record CurrentUser(Long userId, UserRole role, Long organizationId) {

    /** ADMIN 은 마스터 권한 (api-spec.md §0-4) */
    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    /**
     * 해당 조직 소속인지. 조직 유형(기업·기관·대학)이나 역할과 무관하게 소속만 봅니다.
     * 기업도 자기 조직 장비를 등록하고 그 장비로 들어온 의뢰를 처리할 수 있습니다. 관리자는 항상 true
     */
    public boolean belongsTo(Long targetOrganizationId) {
        return isAdmin() || (targetOrganizationId != null && targetOrganizationId.equals(organizationId));
    }
}
