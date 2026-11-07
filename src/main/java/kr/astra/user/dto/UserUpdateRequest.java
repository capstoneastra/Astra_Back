package kr.astra.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PATCH /users/me: 보낸 필드만 바뀝니다 (null 이면 그대로).
 * 이메일·역할·소속은 여기서 바꿀 수 없습니다.
 */
public record UserUpdateRequest(
        @Size(min = 1, max = 50) @Pattern(regexp = ".*\\S.*", message = "공백만 입력할 수 없습니다.") String name,
        @Size(max = 30) String phone) {
}
