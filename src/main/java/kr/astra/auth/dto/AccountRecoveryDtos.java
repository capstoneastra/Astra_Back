package kr.astra.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import kr.astra.global.validation.BcryptPassword;

/** 계정 찾기·비밀번호 재설정 (api-spec §1-2, 로그인 불필요) */
public final class AccountRecoveryDtos {

    private AccountRecoveryDtos() {
    }

    /** POST /auth/find-email: 가입할 때 넣은 이름과 연락처 (연락처는 숫자만 비교) */
    public record FindEmailRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 30) String phone) {
    }

    /** 일치하는 계정의 가려진 이메일 (예: co*****@astra.kr). 없으면 빈 배열 */
    public record FindEmailResponse(List<String> emails) {
    }

    /** POST /auth/password-resets */
    public record PasswordResetRequest(@NotBlank @Email @Size(max = 200) String email) {
    }

    /** POST /auth/password-resets/confirm */
    public record PasswordResetConfirmRequest(
            @NotBlank @Email @Size(max = 200) String email,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "인증 코드는 숫자 6자리입니다.") String code) {
    }

    /**
     * @param resetToken 새 비밀번호 저장에 쓰는 일회용 토큰
     * @param expiresIn  토큰 유효 시간(초)
     */
    public record PasswordResetConfirmResponse(String resetToken, long expiresIn) {
    }

    /** POST /auth/password-resets/complete. 비밀번호 규칙은 회원가입과 같음 (8~72자) */
    public record PasswordResetCompleteRequest(
            @NotBlank @Size(max = 200) String resetToken,
            @NotBlank @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하로 입력해 주세요.")
            @BcryptPassword String newPassword) {

        @Override
        public String toString() {
            return "PasswordResetCompleteRequest[****]"; // 로그에 토큰·비밀번호가 남지 않게
        }
    }
}
