package kr.astra.auth.dto;

/**
 * POST /auth/email-verifications/confirm → 200
 *
 * @param verificationToken 회원가입(POST /auth/signup)에 같이 보내는 토큰. 한 번만 쓸 수 있음
 * @param expiresIn         토큰 유효 시간(초)
 */
public record EmailVerificationConfirmResponse(String verificationToken, long expiresIn) {
}
