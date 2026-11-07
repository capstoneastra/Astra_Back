package kr.astra.auth.dto;

import kr.astra.user.entity.UserStatus;

/** POST /auth/signup → 201. 기관 담당자는 PENDING (관리자 승인 전 로그인 시 403 ACCOUNT_NOT_ACTIVE) */
public record SignupResponse(Long userId, UserStatus status) {
}
