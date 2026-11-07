package kr.astra.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import kr.astra.auth.dto.LoginRequest;
import kr.astra.auth.dto.RefreshTokenRequest;
import kr.astra.auth.dto.SignupRequest;
import kr.astra.auth.dto.SignupResponse;
import kr.astra.auth.dto.TokenResponse;
import kr.astra.auth.service.AuthService;
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

    @Operation(summary = "회원가입", description = "기업은 바로 ACTIVE, 기관 담당자는 관리자 승인 전까지 PENDING. 관리자는 가입 불가")
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
