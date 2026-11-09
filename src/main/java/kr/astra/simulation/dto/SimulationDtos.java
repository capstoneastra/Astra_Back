package kr.astra.simulation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import kr.astra.simulation.entity.SimulationCapability;
import kr.astra.simulation.entity.SimulationRunStatus;

/**
 * 가상 시뮬레이션 요청·응답 (api-spec §9). 전부 DEMO — 화면에 capability 와 DEMO 배지를 함께 표시하고,
 * "실장비 시뮬레이션 가능" 같은 표현은 쓰지 않습니다 (README v0.6 정책).
 */
public final class SimulationDtos {

    private SimulationDtos() {
    }

    /**
     * GET /simulation/models 한 줄
     *
     * @param isCalculated 서버가 실제로 계산하는 모델이면 true (false 면 프리셋 표시만, 실행하면 NOT_EXECUTED)
     */
    public record ModelView(
            String modelType,
            String description,
            List<String> requiredInputFields,
            String limitation,
            SimulationCapability capability,
            String modelVersion,
            boolean isCalculated,
            boolean isMock) {
    }

    /** GET /equipment/{id}/simulation-presets 한 줄 */
    public record PresetView(
            String presetId,
            Long equipmentId,
            String modelType,
            String simulationType,
            SimulationCapability capability,
            boolean isCalculated,
            List<String> requiredInputFields,
            Map<String, Object> inputParameters,
            Object specsSnapshot,
            Object assumptions,
            String limitation,
            boolean isMock) {
    }

    /**
     * POST /simulation/runs
     *
     * @param inputParameters 프리셋 값 중 바꿀 것만 보내도 됨 (모델의 requiredInputFields 외 키는 400)
     * @param requirementId   (선택) 본인 시험조건과 연결
     */
    public record RunRequest(
            @NotBlank @Size(max = 20) String presetId,
            Map<String, Object> inputParameters,
            Long requirementId) {
    }

    /** 실행 결과 (warning 은 항상 포함) */
    public record RunView(
            Long id,
            String presetId,
            Long equipmentId,
            Long requirementId,
            SimulationRunStatus status,
            String modelType,
            String modelVersion,
            SimulationCapability capability,
            boolean isMock,
            Map<String, Object> inputParameters,
            Map<String, Object> output,
            String warning,
            String limitation,
            OffsetDateTime createdAt) {
    }
}
