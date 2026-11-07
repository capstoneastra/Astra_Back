package kr.astra.equipment.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.astra.equipment.dto.EquipmentCandidate;
import kr.astra.equipment.dto.EquipmentSpecView;
import kr.astra.equipment.entity.Equipment;
import kr.astra.equipment.repository.EquipmentJdbcRepository;
import kr.astra.equipment.repository.EquipmentRepository;
import kr.astra.equipment.repository.EquipmentSpecRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [BE-A 제공 → BE-C 사용] 매칭용 장비 조회 구현.
 * 후보는 활성 장비만, 시연용 MOCK 장비도 포함합니다 (매칭 결과의 usedMockData 로 표시).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipmentQueryServiceImpl implements EquipmentQueryService {

    private final EquipmentRepository equipmentRepository;
    private final EquipmentSpecRepository specRepository;
    private final EquipmentJdbcRepository jdbcRepository;
    private final EquipmentService equipmentService;

    @Override
    public List<EquipmentCandidate> findCandidates(String testTypeCode, String region) {
        List<Long> ids = jdbcRepository.findCandidateIds(testTypeCode, region);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Equipment> byId = equipmentRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Equipment::getId, Function.identity()));
        Map<Long, String> names = equipmentService.institutionNames(byId.values().stream()
                .map(Equipment::getInstitutionId).collect(Collectors.toSet()));
        return ids.stream().map(byId::get)
                .map(e -> new EquipmentCandidate(e.getId(), e.getExternalId(), e.getName(), e.getInstitutionId(),
                        names.get(e.getInstitutionId()), e.getRegion(), e.isMock()))
                .toList();
    }

    /** 사양 전체 (같은 키면 REAL 이 먼저). 없는 장비면 빈 목록 */
    @Override
    public List<EquipmentSpecView> getSpecs(Long equipmentId) {
        return EquipmentService.orderRealFirst(specRepository.findAllByEquipmentIdOrderByIdAsc(equipmentId)).stream()
                .map(s -> new EquipmentSpecView(s.getSpecKey(), s.getLabel(), s.getValueNum(), s.getMinNum(),
                        s.getMaxNum(), s.getValueText(), s.getUnit(), s.getSourceType()))
                .toList();
    }
}
