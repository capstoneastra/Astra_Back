package kr.astra.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.astra.user.entity.UserRole;

/**
 * POST /auth/signup (api-spec §1 가입 흐름)
 * <ul>
 *   <li>COMPANY_USER: organization(name, businessRegNo) 필수 → 기업 조직 생성, 바로 ACTIVE</li>
 *   <li>INSTITUTION_MANAGER + organizationId: 승인된 기존 기관 선택 → PENDING</li>
 *   <li>INSTITUTION_MANAGER + organization(name, address, phone): 미등록 기관 신청 → PENDING</li>
 *   <li>ADMIN: 가입 불가</li>
 * </ul>
 */
public record SignupRequest(
        @NotBlank @Email @Size(max = 200) String email,
        @NotBlank @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하로 입력해 주세요.") String password,
        @NotBlank @Size(max = 50) String name,
        @Size(max = 30) String phone,
        @NotNull UserRole role,
        Long organizationId,
        @Valid OrganizationInput organization) {

    /** 가입하면서 새로 만드는 조직 (기업, 또는 미등록 기관) */
    public record OrganizationInput(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 20) String businessRegNo,
            @Size(max = 300) String address,
            @Size(max = 30) String phone) {
    }
}
