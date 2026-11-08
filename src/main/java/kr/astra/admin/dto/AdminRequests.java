package kr.astra.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.astra.user.entity.UserStatus;

/** 관리자 API 요청 본문 (api-spec §12) */
public final class AdminRequests {

    private AdminRequests() {
    }

    /** PATCH /admin/users/{id}/status: SUSPENDED(정지) 또는 ACTIVE(해제) */
    public record ChangeStatusRequest(@NotNull UserStatus status) {
    }

    /** POST /admin/organizations: 기관 직접 등록 (바로 승인됨) */
    public record CreateOrganizationRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String parentName,
            @Size(max = 50) String region,
            @Size(max = 300) String address,
            @Size(max = 30) String phone,
            @Email @Size(max = 200) String email,
            @Size(max = 500) String homepageUrl) {
    }

    /** PATCH /admin/organizations/{id}: 보낸 값만 수정 */
    public record UpdateOrganizationRequest(
            @Size(min = 1, max = 200) String name,
            @Size(max = 200) String parentName,
            @Size(max = 20) String businessRegNo,
            @Size(max = 50) String region,
            @Size(max = 300) String address,
            @Size(max = 30) String phone,
            @Email @Size(max = 200) String email,
            @Size(max = 500) String homepageUrl) {
    }
}
