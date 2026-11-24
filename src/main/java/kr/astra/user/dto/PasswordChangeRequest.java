package kr.astra.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.astra.global.validation.BcryptPassword;

/**
 * PATCH /users/me/password. 새 비밀번호 규칙은 회원가입(SignupRequest.password)과 같습니다.
 */
public record PasswordChangeRequest(
        @NotBlank @Size(max = 200) String currentPassword,
        @NotBlank @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하로 입력해 주세요.")
        @BcryptPassword String newPassword) {

    @Override
    public String toString() {
        return "PasswordChangeRequest[****]"; // 로그에 비밀번호가 남지 않게
    }
}
