package kr.astra.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.astra.admin.dto.AdminRequests.CreateOrganizationRequest;
import kr.astra.admin.dto.AdminRequests.UpdateOrganizationRequest;
import kr.astra.admin.service.AdminOrganizationService;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.response.PageResponse;
import kr.astra.global.security.CurrentUser;
import kr.astra.organization.dto.AdminOrganizationResponse;
import kr.astra.organization.entity.OrgType;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 · 조직", description = "조직 목록, 기관 직접 등록·승인·수정 (api-spec §12, 관리자 전용)")
@RestController
@RequestMapping("/admin/organizations")
@RequiredArgsConstructor
public class AdminOrganizationController {

    private final AdminOrganizationService service;

    @Operation(summary = "조직 목록", description = "?orgType=INSTITUTION&verified=false 처럼 거를 수 있음. "
            + "기본 정렬 최신 등록순, 정렬은 createdAt·id 만")
    @GetMapping
    public ApiResponse<PageResponse<AdminOrganizationResponse>> list(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) OrgType orgType,
            @RequestParam(required = false) Boolean verified,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ApiResponse.ok(service.list(user, orgType, verified, pageable));
    }

    @Operation(summary = "기관 직접 등록", description = "시험기관(INSTITUTION)으로 바로 승인된 상태로 등록. "
            + "같은 이름의 기관이 있으면 409 ORGANIZATION_ALREADY_EXISTS")
    @PostMapping
    public ResponseEntity<ApiResponse<AdminOrganizationResponse>> create(
            @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody CreateOrganizationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(user, request)));
    }

    @Operation(summary = "조직 승인", description = "is_verified=true (이미 승인됐으면 그대로). "
            + "가입 신청 회원을 승인하면 그 회원이 만든 조직도 자동 승인되므로, 회원 없이 조직만 승인할 때 사용")
    @PostMapping("/{id}/verify")
    public ApiResponse<AdminOrganizationResponse> verify(@AuthenticationPrincipal CurrentUser user,
                                                         @PathVariable Long id) {
        return ApiResponse.ok(service.verify(user, id));
    }

    @Operation(summary = "조직 정보 수정", description = "보낸 값만 수정. 이름이 겹치면 409 ORGANIZATION_ALREADY_EXISTS")
    @PatchMapping("/{id}")
    public ApiResponse<AdminOrganizationResponse> update(@AuthenticationPrincipal CurrentUser user,
                                                         @PathVariable Long id,
                                                         @Valid @RequestBody UpdateOrganizationRequest request) {
        return ApiResponse.ok(service.update(user, id, request));
    }
}
