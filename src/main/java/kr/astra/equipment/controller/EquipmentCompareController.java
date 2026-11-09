package kr.astra.equipment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import kr.astra.equipment.dto.EquipmentInputs.CompareRequest;
import kr.astra.equipment.dto.EquipmentManageResponses.Comparison;
import kr.astra.equipment.dto.EquipmentManageResponses.DuplicateGroup;
import kr.astra.equipment.dto.EquipmentManageResponses.Similar;
import kr.astra.equipment.service.EquipmentCompareService;
import kr.astra.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "장비 비교·중복", description = "유사 장비, 지역 내 중복 장비, 사양 비교표 (api-spec §2, 기능 12)")
@RestController
@RequestMapping("/equipment")
@RequiredArgsConstructor
public class EquipmentCompareController {

    private final EquipmentCompareService compareService;

    @Operation(summary = "유사 장비", description = "같은 시험유형·같은 분류·이름 유사(0.3 이상) 장비. "
            + "시험유형이 겹치는 장비 → 이름 유사도 순, 최대 10개. reasons 에 이유")
    @GetMapping("/{id}/similar")
    public ApiResponse<List<Similar>> similar(@PathVariable Long id) {
        return ApiResponse.ok(compareService.similar(id));
    }

    @Operation(summary = "지역 내 중복 장비 그룹", description = "같은 지역 + 같은 시험유형에 장비가 2대 이상인 그룹. 장비 수 많은 순")
    @GetMapping("/duplicates")
    public ApiResponse<List<DuplicateGroup>> duplicates(
            @Parameter(description = "예: 대전") @RequestParam(required = false) String region,
            @Parameter(description = "예: VIBRATION") @RequestParam(required = false) String testType) {
        return ApiResponse.ok(compareService.duplicates(region, testType));
    }

    @Operation(summary = "장비 사양·이용 조건 비교표", description = "장비 2~5대. 사양 키별 한 줄(칸마다 REAL 이 있으면 REAL·없으면 MOCK) "
            + "+ 장비별 이용 조건(요금·소요 기간·가동률·가용 상태). testType 을 보내면 그 시험의 예상 비용·소요일도. "
            + "없거나 비활성 장비가 있으면 404 EQUIPMENT_NOT_FOUND (details.equipmentIds), 없는 testType 은 400")
    @PostMapping("/compare")
    public ApiResponse<Comparison> compare(@Valid @RequestBody CompareRequest request) {
        return ApiResponse.ok(compareService.compare(request.equipmentIds(), request.testType()));
    }
}
