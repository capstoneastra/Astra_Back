package kr.astra.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** POST /auth/email-verifications/confirm: 메일로 받은 6자리 코드 확인 */
public record EmailVerificationConfirmRequest(
        @NotBlank @Email @Size(max = 200) String email,
        @NotBlank @Pattern(regexp = "\\d{6}", message = "인증 코드는 숫자 6자리입니다.") String code) {
}
