package kr.astra.equipment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import kr.astra.equipment.dto.EquipmentDetailResponse;
import kr.astra.equipment.dto.EquipmentSearchCondition;
import kr.astra.equipment.dto.EquipmentSummaryResponse;
import kr.astra.equipment.entity.Availability;
import kr.astra.equipment.entity.EquipmentType;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "장비", description = "장비 검색·목록·상세 (api-spec §2, 기능 1·2)")
@RestController
@RequestMapping("/equipment")
@RequiredArgsConstructor
public class EquipmentController {

    private final EquipmentService equipmentService;

    @Operation(summary = "장비 통합 검색/목록", description = "활성 장비만. q 가 있으면 이름이 비슷한 순서가 먼저. "
            + "정렬은 externalId(기본)·infoUpdatedAt 만 가능. isMock=true 인 장비는 화면에 DEMO 표시")
    @GetMapping
    public ApiResponse<PageResponse<EquipmentSummaryResponse>> search(
            @Parameter(description = "장비명·모델·제조사·시험유형(이름·코드·키워드) 부분 일치. 예: 진동")
            @RequestParam(required = false) String q,
            @Parameter(description = "지역, 여러 개 가능: region=대전&region=진주")
            @RequestParam(required = false) List<String> region,
            @RequestParam(required = false) Long institutionId,
            @RequestParam(required = false) EquipmentType equipmentType,
            @Parameter(description = "시험유형 코드. 예: VIBRATION")
            @RequestParam(required = false) String testType,
            @RequestParam(required = false) Availability availability,
            @Parameter(description = "true 면 외부 이용 가능(EXTERNAL_ALLOWED) 장비만")
            @RequestParam(defaultValue = "false") boolean externalOnly,
            @Parameter(description = "false 면 시연용 가상 데이터가 섞인 장비 제외 (기본 true)")
            @RequestParam(defaultValue = "true") boolean includeMock,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(equipmentService.search(new EquipmentSearchCondition(q, region, institutionId,
                equipmentType, testType, availability, externalOnly, includeMock), pageable));
    }

    @Operation(summary = "장비 상세", description = "이용 조건·사양(REAL·MOCK 모두, 같은 키면 REAL 먼저)·출처·시뮬레이션. "
            + "없거나 비활성이면 404 EQUIPMENT_NOT_FOUND")
    @GetMapping("/{id}")
    public ApiResponse<EquipmentDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(equipmentService.getDetail(id));
    }
}
