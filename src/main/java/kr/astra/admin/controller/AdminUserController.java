package kr.astra.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.astra.admin.dto.AdminRequests.ChangeStatusRequest;
import kr.astra.admin.dto.AdminUserResponse;
import kr.astra.admin.dto.RejectUserRequest;
import kr.astra.admin.service.AdminUserService;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.response.PageResponse;
import kr.astra.global.security.CurrentUser;
import kr.astra.user.entity.UserRole;
import kr.astra.user.entity.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 · 회원", description = "회원 목록, 가입 승인·거절, 정지·해제 (api-spec §12, 관리자 전용)")
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(summary = "회원 목록", description = "?role=COMPANY_USER&status=PENDING 처럼 거를 수 있음. 기본 정렬은 가입 최신순")
    @GetMapping
    public ApiResponse<PageResponse<AdminUserResponse>> list(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) UserStatus status,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ApiResponse.ok(adminUserService.list(user, role, status, pageable));
    }

    @Operation(summary = "가입 승인", description = "PENDING → ACTIVE. 가입하며 만든 조직(기업·신규 기관)도 함께 승인. "
            + "PENDING 이 아니면 409 USER_INVALID_STATUS")
    @PostMapping("/{id}/approve")
    public ApiResponse<AdminUserResponse> approve(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        return ApiResponse.ok(adminUserService.approve(user, id));
    }

    @Operation(summary = "회원 정지·해제", description = "ACTIVE → SUSPENDED(정지), SUSPENDED → ACTIVE(해제)만 가능. "
            + "관리자 계정·그 외 상태면 409 USER_INVALID_STATUS. 정지하면 refresh 토큰이 모두 폐기되고, "
            + "이미 받은 access 토큰도 다음 요청부터 바로 403 ACCOUNT_NOT_ACTIVE (해제하면 바로 통과)")
    @PatchMapping("/{id}/status")
    public ApiResponse<AdminUserResponse> changeStatus(@AuthenticationPrincipal CurrentUser user,
                                                       @PathVariable Long id,
                                                       @Valid @RequestBody ChangeStatusRequest request) {
        return ApiResponse.ok(adminUserService.changeStatus(user, id, request.status()));
    }

    @Operation(summary = "가입 거절", description = "PENDING → REJECTED. 사유는 신청자가 로그인할 때 보임. "
            + "PENDING 이 아니면 409 USER_INVALID_STATUS")
    @PostMapping("/{id}/reject")
    public ApiResponse<AdminUserResponse> reject(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
                                                 @Valid @RequestBody RejectUserRequest request) {
        return ApiResponse.ok(adminUserService.reject(user, id, request.reason()));
    }
}
