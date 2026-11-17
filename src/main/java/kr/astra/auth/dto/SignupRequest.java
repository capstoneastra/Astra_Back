package kr.astra.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.astra.organization.entity.OrgType;
import kr.astra.user.entity.UserRole;

/**
 * POST /auth/signup (api-spec §1 가입 흐름). 먼저 이메일 인증을 마치고 받은 verificationToken 을 같이 보냅니다.
 * 기업·기관·대학 담당자 모두 PENDING 으로 시작하고, 플랫폼 관리자가 승인하면 ACTIVE.
 * <ul>
 *   <li>COMPANY_USER: organization(name, businessRegNo) 필수 → 기업 조직 생성 → PENDING</li>
 *   <li>INSTITUTION_MANAGER + organizationId: 승인된 기존 기관·대학 선택 → PENDING</li>
 *   <li>INSTITUTION_MANAGER + organization(name, address, phone, orgType): 미등록 기관·대학 신청 → PENDING.
 *       orgType 은 INSTITUTION(기본값) 또는 UNIVERSITY</li>
 *   <li>ADMIN: 가입 불가</li>
 * </ul>
 */
public record SignupRequest(
        @NotBlank @Email @Size(max = 200) String email,
        @NotBlank(message = "이메일 인증을 먼저 완료해 주세요.") @Size(max = 200) String verificationToken,
        @NotBlank @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하로 입력해 주세요.") String password,
        @NotBlank @Size(max = 50) String name,
        @Size(max = 30) String phone,
        @NotNull UserRole role,
        Long organizationId,
        @Valid OrganizationInput organization) {

    /** 가입하면서 새로 만드는 조직 (기업, 또는 미등록 기관·대학). orgType 은 기관 담당자 신청에서만 사용 */
    public record OrganizationInput(
            OrgType orgType,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 20) String businessRegNo,
            @Size(max = 300) String address,
            @Size(max = 30) String phone) {
    }
}
