package kr.astra.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /auth/email-verifications: 인증 코드 발송 */
public record EmailVerificationRequest(@NotBlank @Email @Size(max = 200) String email) {
}
