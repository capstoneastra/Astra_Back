package kr.astra.admin.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.astra.admin.dto.AdminUserResponse;
import kr.astra.global.response.PageResponse;
import kr.astra.global.security.AccessGuard;
import kr.astra.global.security.CurrentUser;
import kr.astra.notification.entity.NotificationType;
import kr.astra.notification.service.NotificationService;
import kr.astra.organization.dto.OrganizationAdminResponse;
import kr.astra.organization.service.OrganizationService;
import kr.astra.user.dto.UserAdminView;
import kr.astra.user.entity.UserRole;
import kr.astra.user.entity.UserStatus;
import kr.astra.user.service.RefreshTokenService;
import kr.astra.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 회원 관리 (api-spec §12): 회원 목록, 가입 승인·거절.
 * 승인하면 그 회원이 가입하며 만든 조직(기업·신규 기관)도 함께 승인(is_verified=TRUE)합니다.
 * 승인·거절 알림(USER_APPROVED / USER_REJECTED)은 BE-B 알림 구현이 있을 때만 보냅니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserService {

    private final UserService userService;
    private final OrganizationService organizationService;
    private final ObjectProvider<NotificationService> notificationService;
    private final RefreshTokenService refreshTokenService;

    public PageResponse<AdminUserResponse> list(CurrentUser admin, UserRole role, UserStatus status,
                                                Pageable pageable) {
        AccessGuard.requireAdmin(admin);
        Page<UserAdminView> users = userService.searchForAdmin(role, status, pageable);
        Map<Long, OrganizationAdminResponse> orgs = organizationService.findAdminViews(
                users.getContent().stream().map(UserAdminView::organizationId).filter(Objects::nonNull).toList());
        return PageResponse.from(users, u -> AdminUserResponse.of(u, orgs.get(u.organizationId())));
    }

    @Transactional
    public AdminUserResponse approve(CurrentUser admin, Long userId) {
        AccessGuard.requireAdmin(admin);
        UserAdminView user = userService.approve(userId, admin.userId());
        organizationService.verify(user.organizationId());
        notificationService.ifAvailable(n -> n.notify(user.id(), NotificationType.USER_APPROVED,
                "회원가입이 승인되었습니다.", "/", null));
        return toResponse(user);
    }

    @Transactional
    public AdminUserResponse reject(CurrentUser admin, Long userId, String reason) {
        AccessGuard.requireAdmin(admin);
        UserAdminView user = userService.reject(userId, reason);
        notificationService.ifAvailable(n -> n.notify(user.id(), NotificationType.USER_REJECTED,
                "회원가입이 거절되었습니다.", null, null));
        return toResponse(user);
    }

    /** 정지(SUSPENDED)·해제(ACTIVE). 정지하면 그 회원의 refresh 토큰을 모두 폐기해 토큰 재발급을 바로 막음 */
    @Transactional
    public AdminUserResponse changeStatus(CurrentUser admin, Long userId, UserStatus status) {
        AccessGuard.requireAdmin(admin);
        UserAdminView user = userService.changeStatus(userId, admin.userId(), status);
        if (status == UserStatus.SUSPENDED) {
            refreshTokenService.revokeAll(userId);
        }
        return toResponse(user);
    }

    private AdminUserResponse toResponse(UserAdminView user) {
        OrganizationAdminResponse org = user.organizationId() == null ? null
                : organizationService.findAdminViews(List.of(user.organizationId())).get(user.organizationId());
        return AdminUserResponse.of(user, org);
    }
}
