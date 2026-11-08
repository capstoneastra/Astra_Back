package kr.astra.equipment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.astra.equipment.dto.EquipmentDetailResponse;
import kr.astra.equipment.dto.EquipmentInputs.CreateRequest;
import kr.astra.equipment.dto.EquipmentInputs.SpecsRequest;
import kr.astra.equipment.dto.EquipmentInputs.TestTypesRequest;
import kr.astra.equipment.dto.EquipmentInputs.UpdateRequest;
import kr.astra.equipment.dto.EquipmentManageResponses.ChangeLog;
import kr.astra.equipment.dto.EquipmentManageResponses.Created;
import kr.astra.equipment.service.EquipmentCommandService;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.response.PageResponse;
import kr.astra.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "기관 장비 관리", description = "소속 기관 장비 등록·수정·사양·시험유형·비활성화·확인·변경 이력 (api-spec §2-1). "
        + "기관 담당자는 소속 기관 장비만 (아니면 403 FORBIDDEN), 관리자는 전부")
@RestController
@RequestMapping("/equipment")
@RequiredArgsConstructor
public class EquipmentManageController {

    private final EquipmentCommandService commandService;

    @Operation(summary = "장비 등록", description = "소속 기관으로 등록, 외부 id ASTRA-EQ-{id 4자리}, 바로 INSTITUTION_VERIFIED. "
            + "사양은 REAL 로 저장. 관리자는 institutionId 필수")
    @PostMapping
    public ResponseEntity<ApiResponse<Created>> create(@AuthenticationPrincipal CurrentUser user,
                                                       @Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(commandService.create(user, request)));
    }

    @Operation(summary = "장비 정보 수정", description = "보낸 값만 수정 → INSTITUTION_VERIFIED, infoUpdatedAt 갱신. 응답은 장비 상세")
    @PatchMapping("/{id}")
    public ApiResponse<EquipmentDetailResponse> update(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
                                                       @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(commandService.update(user, id, request));
    }

    @Operation(summary = "실제 사양 일괄 저장", description = "REAL 사양 전체를 이 목록으로 교체 (빈 목록이면 REAL 전부 삭제). "
            + "MOCK 사양은 수정되지 않음")
    @PutMapping("/{id}/specs")
    public ApiResponse<EquipmentDetailResponse> replaceSpecs(@AuthenticationPrincipal CurrentUser user,
                                                             @PathVariable Long id,
                                                             @Valid @RequestBody SpecsRequest request) {
        return ApiResponse.ok(commandService.replaceSpecs(user, id, request.specs()));
    }

    @Operation(summary = "가능 시험유형 저장", description = "가능 시험유형 + 예상 비용·소요일 전체를 이 목록으로 교체")
    @PutMapping("/{id}/test-types")
    public ApiResponse<EquipmentDetailResponse> replaceTestTypes(@AuthenticationPrincipal CurrentUser user,
                                                                 @PathVariable Long id,
                                                                 @Valid @RequestBody TestTypesRequest request) {
        return ApiResponse.ok(commandService.replaceTestTypes(user, id, request.testTypes()));
    }

    @Operation(summary = "장비 비활성화", description = "is_active=false. 기존 의뢰 이력은 보존되고 목록·상세에서 안 보임")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deactivate(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        commandService.deactivate(user, id);
        return ApiResponse.ok();
    }

    @Operation(summary = "현재 정보 확인", description = "정보가 맞다는 확인만 → INSTITUTION_VERIFIED, infoUpdatedAt=now")
    @PostMapping("/{id}/confirm")
    public ApiResponse<EquipmentDetailResponse> confirm(@AuthenticationPrincipal CurrentUser user,
                                                        @PathVariable Long id) {
        return ApiResponse.ok(commandService.confirm(user, id));
    }

    @Operation(summary = "변경 이력", description = "최신순. changes = {\"필드\": {\"before\", \"after\"}}")
    @GetMapping("/{id}/change-logs")
    public ApiResponse<PageResponse<ChangeLog>> changeLogs(@AuthenticationPrincipal CurrentUser user,
                                                           @PathVariable Long id,
                                                           @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(commandService.changeLogs(user, id, pageable));
    }
}
