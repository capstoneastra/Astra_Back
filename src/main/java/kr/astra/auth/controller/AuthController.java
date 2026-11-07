package kr.astra.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import kr.astra.auth.dto.EmailVerificationConfirmRequest;
import kr.astra.auth.dto.EmailVerificationConfirmResponse;
import kr.astra.auth.dto.EmailVerificationRequest;
import kr.astra.auth.dto.EmailVerificationSentResponse;
import kr.astra.auth.dto.LoginRequest;
import kr.astra.auth.dto.RefreshTokenRequest;
import kr.astra.auth.dto.SignupRequest;
import kr.astra.auth.dto.SignupResponse;
import kr.astra.auth.dto.TokenResponse;
import kr.astra.auth.service.AuthService;
import kr.astra.auth.service.EmailVerificationService;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.security.AccessGuard;
import kr.astra.global.security.CurrentUser;
import kr.astra.organization.dto.InstitutionOptionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "회원가입·로그인·토큰 (api-spec §1)")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;

    @Operation(summary = "이메일 인증 코드 발송", description = "6자리 코드를 메일로 보냄(10분 유효). "
            + "이미 가입된 이메일은 409 EMAIL_ALREADY_EXISTS, 60초 안에 다시 요청하면 429 TOO_MANY_REQUESTS. "
            + "MAIL_HOST 가 없는 로컬·시연 서버는 메일 대신 서버 로그에 코드를 출력")
    @PostMapping("/email-verifications")
    public ApiResponse<EmailVerificationSentResponse> sendVerificationCode(
            @Valid @RequestBody EmailVerificationRequest request) {
        return ApiResponse.ok(emailVerificationService.sendCode(request.email()));
    }

    @Operation(summary = "이메일 인증 코드 확인", description = "맞으면 회원가입에 쓸 verificationToken(30분 유효) 발급. "
            + "틀리면 400 VERIFICATION_CODE_INVALID(details.remainingAttempts), "
            + "만료·5회 틀림이면 400 VERIFICATION_CODE_EXPIRED → 코드 다시 받기")
    @PostMapping("/email-verifications/confirm")
    public ApiResponse<EmailVerificationConfirmResponse> confirmVerificationCode(
            @Valid @RequestBody EmailVerificationConfirmRequest request) {
        return ApiResponse.ok(emailVerificationService.confirm(request.email(), request.code()));
    }

    @Operation(summary = "회원가입", description = "이메일 인증 후 받은 verificationToken 필수(없거나 만료면 400 EMAIL_NOT_VERIFIED). "
            + "기업·기관 담당자 모두 관리자 승인 전까지 PENDING. 관리자는 가입 불가")
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(authService.signup(request)));
    }

    @Operation(summary = "로그인", description = "access 토큰(1시간)과 refresh 토큰(14일) 발급. "
            + "틀리면 401 INVALID_CREDENTIALS, 승인 대기·정지 계정은 403 ACCOUNT_NOT_ACTIVE")
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @Operation(summary = "토큰 재발급", description = "새 access·refresh 토큰 발급. 쓴 refresh 토큰은 폐기됨. "
            + "만료·폐기된 토큰이면 401 AUTH_REQUIRED")
    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken()));
    }

    @Operation(summary = "가입용 기관 목록", description = "승인된 기관의 id·이름·지역 (로그인 불필요)")
    @GetMapping("/institutions")
    public ApiResponse<List<InstitutionOptionResponse>> institutions() {
        return ApiResponse.ok(authService.listInstitutions());
    }

    @Operation(summary = "로그아웃", description = "refresh 토큰 폐기 (로그인 필요)")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal CurrentUser user,
                                    @Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(AccessGuard.requireLogin(user).userId(), request.refreshToken());
        return ApiResponse.ok();
    }
}
