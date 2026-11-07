package kr.astra.auth.dto;

import kr.astra.user.dto.UserAccount;
import kr.astra.user.entity.UserRole;

/**
 * POST /auth/login, POST /auth/refresh → 200
 * <pre>
 * { "accessToken": "eyJ...", "refreshToken": "eyJ...", "expiresIn": 3600,
 *   "user": { "id": 1, "name": "홍길동", "role": "COMPANY_USER", "organizationId": 10 } }
 * </pre>
 *
 * @param expiresIn access 토큰 유효 시간(초)
 */
public record TokenResponse(String accessToken, String refreshToken, long expiresIn, LoginUser user) {

    public record LoginUser(Long id, String name, UserRole role, Long organizationId) {

        public static LoginUser from(UserAccount account) {
            return new LoginUser(account.id(), account.name(), account.role(), account.organizationId());
        }
    }
}
