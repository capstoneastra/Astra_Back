package kr.astra.auth.dto;

/**
 * POST /auth/email-verifications → 200
 *
 * @param expiresIn          코드 유효 시간(초)
 * @param resendAvailableIn  다시 보내기까지 기다려야 하는 시간(초)
 */
public record EmailVerificationSentResponse(long expiresIn, long resendAvailableIn) {
}
