package kr.astra.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /admin/users/{id}/reject: 거절 사유는 신청자가 로그인할 때 details.rejectReason 으로 보임 */
public record RejectUserRequest(
        @NotBlank(message = "거절 사유를 입력해 주세요.") @Size(max = 500) String reason) {
}
