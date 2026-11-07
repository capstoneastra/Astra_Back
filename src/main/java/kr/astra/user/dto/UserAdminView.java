package kr.astra.user.dto;

import java.time.OffsetDateTime;
import kr.astra.user.entity.User;
import kr.astra.user.entity.UserRole;
import kr.astra.user.entity.UserStatus;

/** 관리자 회원 관리용 사용자 정보 (admin 도메인에 넘김). Entity 대신 사용 */
public record UserAdminView(Long id, String email, String name, String phone, UserRole role, UserStatus status,
                            Long organizationId, String rejectReason, OffsetDateTime approvedAt,
                            OffsetDateTime lastLoginAt, OffsetDateTime createdAt) {

    public static UserAdminView from(User user) {
        return new UserAdminView(user.getId(), user.getEmail(), user.getName(), user.getPhone(), user.getRole(),
                user.getStatus(), user.getOrganizationId(), user.getRejectReason(), user.getApprovedAt(),
                user.getLastLoginAt(), user.getCreatedAt());
    }
}
