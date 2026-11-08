package kr.astra.organization.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import kr.astra.equipment.dto.EquipmentManageResponses.Managed;
import kr.astra.equipment.service.EquipmentCommandService;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.response.PageResponse;
import kr.astra.global.security.CurrentUser;
import kr.astra.organization.dto.InstitutionDetailResponse;
import kr.astra.organization.dto.InstitutionSummaryResponse;
import kr.astra.organization.service.InstitutionService;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "기관", description = "시험기관 목록·상세 (api-spec §2)")
@RestController
@RequestMapping("/institutions")
@RequiredArgsConstructor
public class InstitutionController {

    private final InstitutionService institutionService;
    private final EquipmentCommandService equipmentCommandService;

    @Operation(summary = "기관 목록", description = "승인된 기관 전체, 이름 가나다순 (페이지 없음). equipmentCount = 활성 장비 수")
    @GetMapping
    public ApiResponse<List<InstitutionSummaryResponse>> list() {
        return ApiResponse.ok(institutionService.list());
    }

    @Operation(summary = "기관 상세", description = "연락처, 보유 장비 수, 장비 유형별 수, 할 수 있는 시험유형. "
            + "없거나 미승인 기관이면 404 NOT_FOUND")
    @GetMapping("/{id}")
    public ApiResponse<InstitutionDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(institutionService.getDetail(id));
    }

    @Operation(summary = "우리 기관 장비 목록", description = "기관 담당자: 소속 기관 활성 장비. 관리자: institutionId 필수. "
            + "needsUpdate = 기관 확인 전(NOT_CONFIRMED)이거나 마지막 갱신 90일 경과(OUTDATED). 기본 정렬 externalId")
    @GetMapping("/me/equipment")
    public ApiResponse<PageResponse<Managed>> myEquipment(
            @AuthenticationPrincipal CurrentUser user,
            @Parameter(description = "관리자만: 대상 기관 id") @RequestParam(required = false) Long institutionId,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(equipmentCommandService.myEquipment(user, institutionId, pageable));
    }
}
