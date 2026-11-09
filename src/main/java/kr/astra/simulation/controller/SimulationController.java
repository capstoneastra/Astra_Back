package kr.astra.simulation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.response.PageResponse;
import kr.astra.global.security.CurrentUser;
import kr.astra.simulation.dto.SimulationDtos.ModelView;
import kr.astra.simulation.dto.SimulationDtos.PresetView;
import kr.astra.simulation.dto.SimulationDtos.RunRequest;
import kr.astra.simulation.dto.SimulationDtos.RunView;
import kr.astra.simulation.service.SimulationService;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "가상 시뮬레이션", description = "모델 카탈로그·장비 프리셋·실행·결과 (api-spec §9). 전부 DEMO (isMock=true, warning 항상 포함). "
        + "화면에 capability + DEMO 배지를 함께 표시하고 '실장비 시뮬레이션 가능' 표현은 쓰지 않음")
@RestController
@RequiredArgsConstructor
public class SimulationController {

    private final SimulationService simulationService;

    @Operation(summary = "모델 카탈로그", description = "설명·필수 입력·한계(limitation). isCalculated=false 면 프리셋 표시만")
    @GetMapping("/simulation/models")
    public ApiResponse<List<ModelView>> models() {
        return ApiResponse.ok(simulationService.models());
    }

    @Operation(summary = "장비의 시뮬레이션 프리셋")
    @GetMapping("/equipment/{id}/simulation-presets")
    public ApiResponse<List<PresetView>> presets(@PathVariable Long id) {
        return ApiResponse.ok(simulationService.presets(id));
    }

    @Operation(summary = "실행", description = "프리셋 값에 inputParameters 를 덮어써서 계산. "
            + "계산형·규칙형은 SUCCEEDED, 그 외 모델은 NOT_EXECUTED. 입력이 잘못되면 400 (details.field)")
    @PostMapping("/simulation/runs")
    public ResponseEntity<ApiResponse<RunView>> run(@AuthenticationPrincipal CurrentUser user,
                                                    @Valid @RequestBody RunRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(simulationService.run(user, request)));
    }

    @Operation(summary = "실행 결과", description = "실행한 본인만 (관리자는 전체)")
    @GetMapping("/simulation/runs/{id}")
    public ApiResponse<RunView> get(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        return ApiResponse.ok(simulationService.get(user, id));
    }

    @Operation(summary = "내 실행 이력", description = "최신순. ?equipmentId=48")
    @GetMapping("/simulation/runs")
    public ApiResponse<PageResponse<RunView>> myRuns(@AuthenticationPrincipal CurrentUser user,
                                                     @RequestParam(required = false) Long equipmentId,
                                                     @ParameterObject @PageableDefault(size = 20, sort = "createdAt",
                                                             direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(simulationService.myRuns(user, equipmentId, pageable));
    }
}
