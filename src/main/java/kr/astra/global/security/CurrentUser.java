package kr.astra.global.security;

import kr.astra.user.entity.UserRole;

/**
 * 로그인한 사용자 정보. JWT 필터(BE-A)가 SecurityContext 에 principal 로 넣고,
 * 컨트롤러에서는 {@code @AuthenticationPrincipal CurrentUser user} 로 받습니다.
 *
 * @param userId         users.id
 * @param role           COMPANY_USER / INSTITUTION_MANAGER / ADMIN
 * @param organizationId users.organization_id (기관 담당자 소유권 체크에 사용)
 */
public record CurrentUser(Long userId, UserRole role, Long organizationId) {

    /** ADMIN 은 마스터 권한 (api-spec.md §0-4) */
    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    /** 기관 담당자가 해당 기관 소속인지. 관리자는 항상 true */
    public boolean belongsTo(Long institutionId) {
        return isAdmin() || (role == UserRole.INSTITUTION_MANAGER && institutionId != null
                && institutionId.equals(organizationId));
    }
}
