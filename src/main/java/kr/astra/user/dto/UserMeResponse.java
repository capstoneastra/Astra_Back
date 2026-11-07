package kr.astra.user.dto;

import java.time.OffsetDateTime;
import kr.astra.organization.dto.OrganizationSummaryResponse;
import kr.astra.user.entity.User;
import kr.astra.user.entity.UserRole;
import kr.astra.user.entity.UserStatus;

/** GET /users/me: 내 정보 + 소속 조직 (조직이 없으면 organization=null) */
public record UserMeResponse(
        Long id,
        String email,
        String name,
        String phone,
        UserRole role,
        UserStatus status,
        OrganizationSummaryResponse organization,
        OffsetDateTime lastLoginAt,
        OffsetDateTime createdAt) {

    public static UserMeResponse of(User user, OrganizationSummaryResponse organization) {
        return new UserMeResponse(user.getId(), user.getEmail(), user.getName(), user.getPhone(), user.getRole(),
                user.getStatus(), organization, user.getLastLoginAt(), user.getCreatedAt());
    }
}
