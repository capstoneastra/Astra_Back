package kr.astra.equipment.service;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.astra.equipment.dto.EquipmentManageResponses.Comparison;
import kr.astra.equipment.dto.EquipmentManageResponses.DuplicateGroup;
import kr.astra.equipment.dto.EquipmentManageResponses.Similar;
import kr.astra.equipment.dto.EquipmentSummaryResponse;
import kr.astra.equipment.entity.Equipment;
import kr.astra.equipment.entity.EquipmentSpec;
import kr.astra.equipment.entity.SpecSourceType;
import kr.astra.equipment.repository.EquipmentJdbcRepository;
import kr.astra.equipment.repository.EquipmentJdbcRepository.DuplicateRow;
import kr.astra.equipment.repository.EquipmentJdbcRepository.SimilarRow;
import kr.astra.equipment.repository.EquipmentRepository;
import kr.astra.equipment.repository.EquipmentSpecRepository;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.testtype.service.TestTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 유사 장비·중복 장비 그룹·사양 비교 (api-spec §2, 기능 12).
 * 시험유형 이름이 필요해서 EquipmentService 와 따로 둡니다 (TestTypeService 가 EquipmentService 를 쓰므로 순환 방지).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipmentCompareService {

    private static final int SIMILAR_LIMIT = 10;
    private static final double NAME_SIMILAR_THRESHOLD = 0.3;
    private static final Collator KOREAN = Collator.getInstance(Locale.KOREAN);

    private final EquipmentRepository equipmentRepository;
    private final EquipmentSpecRepository specRepository;
    private final EquipmentJdbcRepository jdbcRepository;
    private final EquipmentService equipmentService;
    private final TestTypeService testTypeService;

    /** GET /equipment/{id}/similar: 같은 시험유형 → 이름 유사도 순, 최대 10개. 없는 장비면 404 */
    public List<Similar> similar(Long equipmentId) {
        Equipment me = equipmentRepository.findByIdAndActiveTrue(equipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EQUIPMENT_NOT_FOUND));
        List<SimilarRow> rows = jdbcRepository.findSimilar(equipmentId, SIMILAR_LIMIT);
        List<Long> ids = rows.stream().map(SimilarRow::id).toList();
        Map<Long, EquipmentSummaryResponse> summaries = equipmentService.summaries(ids).stream()
                .collect(Collectors.toMap(EquipmentSummaryResponse::id, Function.identity()));
        Set<String> myTestTypes = new HashSet<>(
                equipmentService.testTypeCodes(List.of(equipmentId)).getOrDefault(equipmentId, List.of()));
        Map<String, String> names = testTypeService.nameMap();
        return rows.stream().filter(r -> summaries.containsKey(r.id())).map(r -> {
            EquipmentSummaryResponse s = summaries.get(r.id());
            List<String> reasons = new ArrayList<>();
            s.testTypes().stream().filter(myTestTypes::contains)
                    .forEach(code -> reasons.add("같은 시험유형: " + names.getOrDefault(code, code)));
            if (r.sameCategory()) {
                reasons.add("같은 분류: " + me.getCategory());
            }
            if (r.nameSimilarity() >= NAME_SIMILAR_THRESHOLD) {
                reasons.add("이름 유사");
            }
            double similarity = Math.round(r.nameSimilarity() * 100) / 100.0;
            return new Similar(s, reasons, similarity);
        }).toList();
    }

    /**
     * GET /equipment/duplicates: 같은 지역 + 같은 시험유형에 활성 장비가 2대 이상인 그룹.
     * 장비 수 많은 순 → 지역 가나다순 → 시험유형 표시 순서.
     */
    public List<DuplicateGroup> duplicates(String region, String testTypeCode) {
        List<DuplicateRow> rows = jdbcRepository.findDuplicateGroups(region, testTypeCode);
        List<Long> allIds = rows.stream().flatMap(r -> r.equipmentIds().stream()).distinct().toList();
        Map<Long, EquipmentSummaryResponse> summaries = equipmentService.summaries(allIds).stream()
                .collect(Collectors.toMap(EquipmentSummaryResponse::id, Function.identity()));
        Map<String, String> names = testTypeService.nameMap();
        Map<String, Integer> order = new LinkedHashMap<>();
        testTypeService.findAllOrdered().forEach(t -> order.put(t.getCode(), t.getDisplayOrder()));
        return rows.stream().map(r -> {
            List<EquipmentSummaryResponse> equipment = r.equipmentIds().stream().map(summaries::get).toList();
            int institutions = (int) equipment.stream().map(e -> e.institution().id()).distinct().count();
            return new DuplicateGroup(r.region(),
                    new DuplicateGroup.TestTypeRef(r.testTypeCode(), names.get(r.testTypeCode())),
                    equipment.size(), institutions, equipment);
        }).sorted(Comparator.comparingInt(DuplicateGroup::equipmentCount).reversed()
                .thenComparing(DuplicateGroup::region, KOREAN)
                .thenComparing(g -> order.getOrDefault(g.testType().code(), Integer.MAX_VALUE)))
                .toList();
    }

    /**
     * POST /equipment/compare: 장비 2~5대의 사양 비교표. 같은 장비를 두 번 넣으면 400, 없는 장비가 있으면 404.
     * 칸마다 같은 키에 REAL 이 있으면 REAL, 없으면 MOCK. 줄 순서는 요청 장비 순서대로 처음 나온 키 순.
     */
    public Comparison compare(List<Long> equipmentIds) {
        if (new HashSet<>(equipmentIds).size() != equipmentIds.size()) {
            String reason = "같은 장비가 두 번 있습니다.";
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, reason,
                    Map.of("fields", List.of(Map.of("field", "equipmentIds", "reason", reason))));
        }
        Set<Long> active = equipmentRepository.findAllById(equipmentIds).stream().filter(Equipment::isActive)
                .map(Equipment::getId).collect(Collectors.toSet());
        List<Long> missing = equipmentIds.stream().filter(id -> !active.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.EQUIPMENT_NOT_FOUND, Map.of("equipmentIds", missing));
        }
        List<EquipmentSummaryResponse> equipment = equipmentService.summaries(equipmentIds);

        // 장비 id → (사양 키 → 쓸 사양: REAL 우선)
        Map<Long, Map<String, EquipmentSpec>> effective = new LinkedHashMap<>();
        equipmentIds.forEach(id -> effective.put(id, new LinkedHashMap<>()));
        Map<String, String> labels = new LinkedHashMap<>();
        for (Long id : equipmentIds) {
            for (EquipmentSpec s : specRepository.findAllByEquipmentIdOrderByIdAsc(id)) {
                labels.putIfAbsent(s.getSpecKey(), s.getLabel());
                effective.get(id).merge(s.getSpecKey(), s,
                        (old, now) -> old.getSourceType() == SpecSourceType.REAL ? old : now);
            }
        }
        boolean usedMock = effective.values().stream().flatMap(m -> m.values().stream())
                .anyMatch(s -> s.getSourceType() == SpecSourceType.MOCK);
        List<Comparison.Row> rows = labels.entrySet().stream().map(entry -> new Comparison.Row(entry.getKey(),
                entry.getValue(), equipmentIds.stream().map(id -> {
                    EquipmentSpec s = effective.get(id).get(entry.getKey());
                    return s == null ? null : new Comparison.Cell(id, s.getValueNum(), s.getMinNum(), s.getMaxNum(),
                            s.getValueText(), s.getUnit(), s.getSourceType());
                }).toList())).toList();
        return new Comparison(equipment, rows, usedMock);
    }
}
