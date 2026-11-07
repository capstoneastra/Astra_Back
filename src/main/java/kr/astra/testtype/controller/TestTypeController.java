package kr.astra.testtype.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import kr.astra.global.response.ApiResponse;
import kr.astra.testtype.dto.TestTypeDetailResponse;
import kr.astra.testtype.dto.TestTypeSummaryResponse;
import kr.astra.testtype.service.TestTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "시험유형", description = "시험유형 목록·시험 정보 안내 (api-spec §2, 기능 3·4)")
@RestController
@RequestMapping("/test-types")
@RequiredArgsConstructor
public class TestTypeController {

    private final TestTypeService testTypeService;

    @Operation(summary = "시험유형 목록", description = "15개 전체, display_order 순. equipmentCount = 그 시험을 할 수 있는 활성 장비 수")
    @GetMapping
    public ApiResponse<List<TestTypeSummaryResponse>> list() {
        return ApiResponse.ok(testTypeService.list());
    }

    @Operation(summary = "시험 정보 안내", description = "입력할 조건 목록(conditionRules)과 가능 기관별 장비 수·예상 비용·기간·이용 범위. "
            + "없는 코드면 404 NOT_FOUND")
    @GetMapping("/{code}")
    public ApiResponse<TestTypeDetailResponse> detail(@PathVariable String code) {
        return ApiResponse.ok(testTypeService.getDetail(code));
    }
}
