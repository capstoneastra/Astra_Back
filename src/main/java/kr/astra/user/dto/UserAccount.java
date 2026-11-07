package kr.astra.user.dto;

import kr.astra.user.entity.User;
import kr.astra.user.entity.UserRole;
import kr.astra.user.entity.UserStatus;

/** 다른 도메인(auth 등)에 넘기는 사용자 요약. Entity 대신 사용 */
public record UserAccount(Long id, String name, UserRole role, UserStatus status, Long organizationId) {

    public static UserAccount from(User user) {
        return new UserAccount(user.getId(), user.getName(), user.getRole(), user.getStatus(),
                user.getOrganizationId());
    }
}
