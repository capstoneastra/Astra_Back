package kr.astra.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.security.AccessGuard;
import kr.astra.global.security.CurrentUser;
import kr.astra.user.dto.PasswordChangeRequest;
import kr.astra.user.dto.UserMeResponse;
import kr.astra.user.dto.UserUpdateRequest;
import kr.astra.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "사용자", description = "내 정보 (api-spec §1)")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "내 정보 + 소속 조직")
    @GetMapping("/me")
    public ApiResponse<UserMeResponse> me(@AuthenticationPrincipal CurrentUser user) {
        return ApiResponse.ok(userService.getMe(AccessGuard.requireLogin(user).userId()));
    }

    @Operation(summary = "내 정보 수정", description = "name, phone 중 보낸 값만 수정")
    @PatchMapping("/me")
    public ApiResponse<UserMeResponse> updateMe(@AuthenticationPrincipal CurrentUser user,
                                                @Valid @RequestBody UserUpdateRequest request) {
        return ApiResponse.ok(userService.updateMe(AccessGuard.requireLogin(user).userId(), request));
    }

    @Operation(summary = "비밀번호 변경", description = "현재 비밀번호가 틀리면 400 INVALID_PASSWORD. "
            + "새 비밀번호 규칙은 회원가입과 같음(8~72자, 72바이트 이하). 성공하면 data 없이 200")
    @PatchMapping("/me/password")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal CurrentUser user,
                                            @Valid @RequestBody PasswordChangeRequest request) {
        userService.changePassword(AccessGuard.requireLogin(user).userId(), request.currentPassword(),
                request.newPassword());
        return ApiResponse.ok();
    }
}
