package kr.astra.admin.dto;

import java.time.OffsetDateTime;
import kr.astra.organization.dto.OrganizationAdminResponse;
import kr.astra.user.dto.UserAdminView;
import kr.astra.user.entity.UserRole;
import kr.astra.user.entity.UserStatus;

/** GET /admin/users 항목, POST /admin/users/{id}/approve·reject 응답 (api-spec §12) */
public record AdminUserResponse(
        Long id,
        String email,
        String name,
        String phone,
        UserRole role,
        UserStatus status,
        OrganizationAdminResponse organization,
        String rejectReason,
        OffsetDateTime approvedAt,
        OffsetDateTime lastLoginAt,
        OffsetDateTime createdAt) {

    public static AdminUserResponse of(UserAdminView user, OrganizationAdminResponse organization) {
        return new AdminUserResponse(user.id(), user.email(), user.name(), user.phone(), user.role(), user.status(),
                organization, user.rejectReason(), user.approvedAt(), user.lastLoginAt(), user.createdAt());
    }
}
