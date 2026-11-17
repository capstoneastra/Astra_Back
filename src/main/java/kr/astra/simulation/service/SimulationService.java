package kr.astra.simulation.service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.response.PageResponse;
import kr.astra.global.security.AccessGuard;
import kr.astra.global.security.CurrentUser;
import kr.astra.requirement.service.RequirementApplicationService;
import kr.astra.simulation.dto.SimulationDtos.ModelView;
import kr.astra.simulation.dto.SimulationDtos.PresetView;
import kr.astra.simulation.dto.SimulationDtos.RunRequest;
import kr.astra.simulation.dto.SimulationDtos.RunView;
import kr.astra.simulation.entity.SimulationModel;
import kr.astra.simulation.entity.SimulationPreset;
import kr.astra.simulation.entity.SimulationRun;
import kr.astra.simulation.entity.SimulationRunStatus;
import kr.astra.simulation.repository.SimulationModelRepository;
import kr.astra.simulation.repository.SimulationPresetRepository;
import kr.astra.simulation.repository.SimulationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 가상 시뮬레이션 (api-spec §9, 기능 15). 결과마다 DEMO 경고 문구를 반드시 넣습니다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SimulationService {

    /** 모든 실행 결과에 붙는 경고 (api-spec §9 예시 문구 그대로) */
    public static final String WARNING = "가상 시연 모델입니다. 실제 장비 성능·시험 적합성을 의미하지 않습니다.";
    private static final String NOT_CALCULATED = "이 모델은 계산하지 않는 시연용 모델입니다 (프리셋 표시만).";
    /** 문자열 입력값 최대 길이 */
    private static final int MAX_TEXT_INPUT = 200;

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {
    };

    private final SimulationModelRepository modelRepository;
    private final SimulationPresetRepository presetRepository;
    private final SimulationRunRepository runRepository;
    private final SimulationEngine engine;
    private final EquipmentService equipmentService;
    private final RequirementApplicationService requirementService;
    private final JsonMapper jsonMapper;

    /** GET /simulation/models */
    public List<ModelView> models() {
        return modelRepository.findAllByOrderByCapabilityAscModelTypeAsc().stream()
                .map(m -> new ModelView(m.getModelType(), m.getDescription(), m.getRequiredInputFields(),
                        m.getLimitation(), m.getCapability(), m.getModelVersion(), engine.supports(m.getModelType()),
                        true))
                .toList();
    }

    /** GET /equipment/{id}/simulation-presets */
    public List<PresetView> presets(Long equipmentId) {
        equipmentService.getDetail(equipmentId); // 없거나 비활성이면 404 EQUIPMENT_NOT_FOUND
        return presetRepository.findAllByEquipmentIdOrderByPresetIdAsc(equipmentId).stream().map(p -> {
            SimulationModel m = model(p.getModelType());
            return new PresetView(p.getPresetId(), p.getEquipmentId(), p.getModelType(), p.getSimulationType(),
                    m.getCapability(), engine.supports(m.getModelType()), m.getRequiredInputFields(),
                    readMap(p.getInputParameters()), readAny(p.getSpecsSnapshot()), readAny(p.getAssumptions()),
                    m.getLimitation(), p.isMock());
        }).toList();
    }

    /** POST /simulation/runs — 프리셋 값에 사용자 입력을 덮어써서 계산 */
    @Transactional
    public RunView run(CurrentUser actor, RunRequest input) {
        AccessGuard.requireLogin(actor);
        SimulationPreset preset = presetRepository.findById(input.presetId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "시뮬레이션 프리셋을 찾을 수 없습니다.",
                        Map.of("presetId", input.presetId())));
        SimulationModel model = model(preset.getModelType());
        if (input.requirementId() != null) {
            requirementService.ownerId(actor, input.requirementId()); // 본인 시험조건인지 확인
        }

        Map<String, Object> params = readMap(preset.getInputParameters());
        if (input.inputParameters() != null) {
            Set<String> allowed = new HashSet<>(model.getRequiredInputFields());
            for (Map.Entry<String, Object> e : input.inputParameters().entrySet()) {
                String key = e.getKey();
                if (!allowed.contains(key)) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "이 모델에 없는 입력입니다: " + key,
                            Map.of("field", "inputParameters." + key, "allowed", model.getRequiredInputFields()));
                }
                requireScalar(key, e.getValue());
            }
            params.putAll(input.inputParameters());
        }

        SimulationRunStatus status;
        Map<String, Object> output;
        if (engine.supports(model.getModelType())) {
            try {
                output = engine.run(model.getModelType(), params, readList(preset.getSpecsSnapshot()));
            } catch (SimulationEngine.InvalidInput e) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, e.getMessage(),
                        Map.of("field", e.field() == null ? "inputParameters" : "inputParameters." + e.field()));
            }
            status = SimulationRunStatus.SUCCEEDED;
        } else {
            output = new LinkedHashMap<>(Map.of("message", NOT_CALCULATED));
            status = SimulationRunStatus.NOT_EXECUTED;
        }

        SimulationRun run = runRepository.save(new SimulationRun(preset.getPresetId(), preset.getEquipmentId(),
                actor.userId(), input.requirementId(), model.getModelType(), model.getModelVersion(),
                jsonMapper.writeValueAsString(params), jsonMapper.writeValueAsString(output), status, true, WARNING));
        return view(run, model);
    }

    /** GET /simulation/runs/{id} — 실행한 본인(관리자는 전체) */
    public RunView get(CurrentUser actor, Long runId) {
        SimulationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "시뮬레이션 실행 기록을 찾을 수 없습니다.", null));
        AccessGuard.requireOwner(actor, run.getUserId());
        return view(run, model(run.getModelType()));
    }

    /** GET /simulation/runs?equipmentId= — 내 실행 이력 */
    public PageResponse<RunView> myRuns(CurrentUser actor, Long equipmentId, Pageable pageable) {
        Long userId = AccessGuard.requireLogin(actor).userId();
        Page<SimulationRun> page = equipmentId == null ? runRepository.findAllByUserId(userId, pageable)
                : runRepository.findAllByUserIdAndEquipmentId(userId, equipmentId, pageable);
        return PageResponse.from(page, r -> view(r, model(r.getModelType())));
    }

    /** 입력값은 짧은 문자열·숫자·true/false 만 (계산하지 않는 모델도 그대로 저장·응답되므로) */
    private static void requireScalar(String key, Object value) {
        boolean ok = value == null || value instanceof Number || value instanceof Boolean
                || value instanceof String s && s.length() <= MAX_TEXT_INPUT;
        if (!ok) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "입력값은 " + MAX_TEXT_INPUT + "자 이하 문자열, 숫자, true/false 만 쓸 수 있습니다: " + key,
                    Map.of("field", "inputParameters." + key));
        }
    }

    private SimulationModel model(String modelType) {
        return modelRepository.findById(modelType)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "시뮬레이션 모델 정보가 없습니다: " + modelType,
                        null));
    }

    private RunView view(SimulationRun r, SimulationModel m) {
        return new RunView(r.getId(), r.getPresetId(), r.getEquipmentId(), r.getRequirementId(), r.getStatus(),
                r.getModelType(), r.getModelVersion(), m.getCapability(), r.isMock(), readMap(r.getInputParameters()),
                r.getOutput() == null ? null : readMap(r.getOutput()), r.getWarning() == null ? WARNING : r.getWarning(),
                m.getLimitation(), r.getCreatedAt());
    }

    private Map<String, Object> readMap(String json) {
        return json == null || json.isBlank() ? new LinkedHashMap<>() : jsonMapper.readValue(json, MAP);
    }

    private List<Map<String, Object>> readList(String json) {
        if (json == null || json.isBlank() || !json.strip().startsWith("[")) {
            return List.of();
        }
        return jsonMapper.readValue(json, LIST);
    }

    private Object readAny(String json) {
        return json == null || json.isBlank() ? null : jsonMapper.readValue(json, Object.class);
    }
}
