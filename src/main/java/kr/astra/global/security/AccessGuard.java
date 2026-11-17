package kr.astra.global.security;

import java.util.Arrays;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.user.entity.UserRole;

/**
 * 권한 체크 공통 유틸 (api-spec §0-4). 서비스·컨트롤러 어디서나 정적 메서드로 호출합니다.
 * <ul>
 *   <li>ADMIN 은 마스터 권한이라 모든 검사를 통과합니다.</li>
 *   <li>로그인하지 않았으면 401 AUTH_REQUIRED, 권한이 없으면 403 FORBIDDEN 을 던집니다.</li>
 * </ul>
 * <pre>
 * // 장비를 보유한 조직(기업·기관·대학)의 구성원만 수정 (equipment.institution_id)
 * AccessGuard.requireInstitution(user, equipment.getInstitutionId());
 * // 장비 보유 조직이 받은 의뢰 검토 (test_requests.institution_id)
 * AccessGuard.requireInstitution(user, request.getInstitutionId());
 * // 본인 데이터 (test_requests.requester_id, chat_sessions.user_id ...)
 * AccessGuard.requireOwner(user, request.getRequesterId());
 * // 의뢰자 본인 또는 장비 보유 조직 구성원 (의뢰 상세처럼 양쪽 다 보는 화면)
 * AccessGuard.requireOwnerOrInstitution(user, request.getRequesterId(), request.getInstitutionId());
 * // 역할만 확인
 * AccessGuard.requireRole(user, UserRole.COMPANY_USER);
 * </pre>
 * 역할만 확인할 때는 메서드에 {@code @PreAuthorize("hasRole('INSTITUTION_MANAGER')")} 를 붙여도 됩니다.
 * (역할 계층 설정으로 ADMIN 은 자동 통과)
 */
public final class AccessGuard {

    private AccessGuard() {
    }

    /** 로그인 사용자를 돌려줍니다. 없으면 401 AUTH_REQUIRED */
    public static CurrentUser requireLogin(CurrentUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
        return user;
    }

    /** 플랫폼 관리자 전용 */
    public static void requireAdmin(CurrentUser user) {
        if (!requireLogin(user).isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /** 지정한 역할 중 하나여야 함. ADMIN 은 항상 통과 */
    public static void requireRole(CurrentUser user, UserRole... roles) {
        CurrentUser u = requireLogin(user);
        if (!u.isAdmin() && Arrays.stream(roles).noneMatch(r -> r == u.role())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /** 해당 조직(장비 보유 조직) 소속이어야 함. 조직 유형·역할은 보지 않음. ADMIN 은 항상 통과 */
    public static void requireInstitution(CurrentUser user, Long institutionId) {
        if (!requireLogin(user).belongsTo(institutionId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /** 본인 데이터여야 함. ADMIN 은 항상 통과 */
    public static void requireOwner(CurrentUser user, Long ownerUserId) {
        if (!isOwner(requireLogin(user), ownerUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /** 본인 데이터이거나, 해당 조직(장비 보유 조직) 소속이어야 함. ADMIN 은 항상 통과 */
    public static void requireOwnerOrInstitution(CurrentUser user, Long ownerUserId, Long institutionId) {
        CurrentUser u = requireLogin(user);
        if (!isOwner(u, ownerUserId) && !u.belongsTo(institutionId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private static boolean isOwner(CurrentUser user, Long ownerUserId) {
        return user.isAdmin() || (ownerUserId != null && ownerUserId.equals(user.userId()));
    }
}
