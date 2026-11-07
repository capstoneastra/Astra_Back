package kr.astra.auth.service;

import io.jsonwebtoken.JwtException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.astra.auth.dto.LoginRequest;
import kr.astra.auth.dto.SignupRequest;
import kr.astra.auth.dto.SignupRequest.OrganizationInput;
import kr.astra.auth.dto.SignupResponse;
import kr.astra.auth.dto.TokenResponse;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.security.JwtProvider;
import kr.astra.notification.entity.NotificationType;
import kr.astra.notification.service.NotificationService;
import kr.astra.organization.dto.InstitutionOptionResponse;
import kr.astra.organization.entity.OrgType;
import kr.astra.organization.service.OrganizationService;
import kr.astra.user.dto.UserAccount;
import kr.astra.user.entity.UserRole;
import kr.astra.user.service.RefreshTokenService;
import kr.astra.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 회원가입·로그인·토큰 재발급·로그아웃 (api-spec §1)
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserService userService;
    private final OrganizationService organizationService;
    private final RefreshTokenService refreshTokenService;
    private final EmailVerificationService emailVerificationService;
    private final ObjectProvider<NotificationService> notificationService;
    private final JwtProvider jwtProvider;

    @Transactional(readOnly = true)
    public List<InstitutionOptionResponse> listInstitutions() {
        return organizationService.listVerifiedInstitutions();
    }

    /**
     * 회원가입. 이메일 인증 토큰을 한 번 사용하고, 기업·기관 담당자 모두 PENDING 으로 만든 뒤
     * 관리자 전원에게 USER_APPROVAL_REQUESTED 알림 (알림 구현이 있을 때만).
     */
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        userService.checkEmailAvailable(request.email());
        if (request.role() == UserRole.ADMIN) {
            throw invalidField("role", "관리자 계정은 회원가입으로 만들 수 없습니다.");
        }
        emailVerificationService.consumeToken(request.email(), request.verificationToken());
        Long organizationId = switch (request.role()) {
            case COMPANY_USER -> createCompany(request);
            case INSTITUTION_MANAGER -> resolveInstitution(request);
            case ADMIN -> throw invalidField("role", "관리자 계정은 회원가입으로 만들 수 없습니다.");
        };
        UserAccount account = userService.register(organizationId, request.email(), request.password(),
                request.name(), request.phone(), request.role());
        notificationService.ifAvailable(notifier -> userService.findActiveAdminIds().forEach(adminId ->
                notifier.notify(adminId, NotificationType.USER_APPROVAL_REQUESTED,
                        "새 회원가입 승인 요청: " + account.name(), "/admin/users?status=PENDING", null)));
        return new SignupResponse(account.id(), account.status());
    }

    private Long createCompany(SignupRequest request) {
        if (request.organizationId() != null) {
            throw invalidField("organizationId", "기업 회원은 기존 조직을 선택하지 않고 organization 으로 회사 정보를 입력합니다.");
        }
        OrganizationInput org = request.organization();
        if (org == null) {
            throw invalidField("organization", "회사명과 사업자번호를 입력해 주세요.");
        }
        if (!StringUtils.hasText(org.businessRegNo())) {
            throw invalidField("organization.businessRegNo", "사업자등록번호를 입력해 주세요.");
        }
        // 진위 확인(국세청 조회)은 하지 않고 형식만 확인. 실제 확인은 관리자 승인 단계에서 함
        String digits = org.businessRegNo().replace("-", "").trim();
        if (!digits.matches("\\d{10}")) {
            throw invalidField("organization.businessRegNo", "사업자등록번호는 숫자 10자리입니다. (예: 123-45-67890)");
        }
        String businessRegNo = digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5);
        return reusableApplication(OrgType.COMPANY, org.name())
                .map(id -> organizationService.reapplyCompany(id, businessRegNo))
                .orElseGet(() -> organizationService.createCompany(org.name(), businessRegNo));
    }

    private Long resolveInstitution(SignupRequest request) {
        boolean hasId = request.organizationId() != null;
        boolean hasNew = request.organization() != null;
        if (hasId == hasNew) {
            throw invalidField("organizationId", "기관 목록에서 선택(organizationId)하거나 새 기관 정보(organization) 중 하나만 보내 주세요.");
        }
        if (hasId) {
            if (!organizationService.isVerifiedInstitution(request.organizationId())) {
                throw invalidField("organizationId", "선택할 수 없는 기관입니다.");
            }
            return request.organizationId();
        }
        OrganizationInput org = request.organization();
        return reusableApplication(OrgType.INSTITUTION, org.name())
                .map(id -> organizationService.reapplyInstitution(id, org.address(), org.phone()))
                .orElseGet(() -> organizationService.createInstitutionApplication(org.name(), org.address(), org.phone()));
    }

    /**
     * 같은 이름의 조직이 있어도 "미승인 + 승인 대기·활성 회원 없음"(거절된 신청만 남은 조직)이면 다시 씁니다.
     * 그 외에는 empty → create 에서 409 ORGANIZATION_ALREADY_EXISTS.
     */
    private Optional<Long> reusableApplication(OrgType orgType, String name) {
        return organizationService.findUnverifiedId(orgType, name)
                .filter(id -> !userService.hasPendingOrActiveMember(id));
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        return issueTokens(userService.authenticate(request.email(), request.password()));
    }

    /**
     * refresh 토큰으로 새 access·refresh 토큰을 발급하고, 쓴 refresh 토큰은 폐기합니다.
     * 서명이 틀렸거나 만료·폐기된 토큰이면 401 AUTH_REQUIRED (다시 로그인).
     */
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        Long tokenUserId;
        try {
            tokenUserId = jwtProvider.parseRefreshToken(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
        Long storedUserId = refreshTokenService.consume(refreshToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
        if (!storedUserId.equals(tokenUserId)) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
        return issueTokens(userService.getActiveAccount(storedUserId));
    }

    @Transactional
    public void logout(Long userId, String refreshToken) {
        refreshTokenService.revoke(userId, refreshToken);
    }

    private TokenResponse issueTokens(UserAccount account) {
        String accessToken = jwtProvider.createAccessToken(account.id(), account.role(), account.organizationId());
        String refreshToken = jwtProvider.createRefreshToken(account.id());
        refreshTokenService.save(account.id(), refreshToken,
                OffsetDateTime.now().plusSeconds(jwtProvider.refreshValiditySeconds()));
        return new TokenResponse(accessToken, refreshToken, jwtProvider.accessValiditySeconds(),
                TokenResponse.LoginUser.from(account));
    }

    /** @Valid 실패와 같은 형식: details.fields[{field, reason}] */
    private static BusinessException invalidField(String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, reason,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
