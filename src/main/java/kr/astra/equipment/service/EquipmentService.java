package kr.astra.equipment.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.astra.equipment.dto.EquipmentDetailResponse;
import kr.astra.equipment.dto.EquipmentSearchCondition;
import kr.astra.equipment.dto.EquipmentSummaryResponse;
import kr.astra.equipment.dto.InstitutionTestTypeStat;
import kr.astra.equipment.entity.Equipment;
import kr.astra.equipment.entity.EquipmentSpec;
import kr.astra.equipment.entity.EquipmentTestType;
import kr.astra.equipment.entity.EquipmentType;
import kr.astra.equipment.entity.SpecSourceType;
import kr.astra.equipment.repository.EquipmentJdbcRepository;
import kr.astra.equipment.repository.EquipmentRepository;
import kr.astra.equipment.repository.EquipmentSpecRepository;
import kr.astra.equipment.repository.EquipmentTestTypeRepository;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.response.PageResponse;
import kr.astra.organization.dto.OrganizationSummaryResponse;
import kr.astra.organization.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장비 조회 (api-spec §2): 목록·검색, 상세, 다른 도메인용 집계.
 * 다른 도메인은 이 서비스(또는 EquipmentQueryService)만 사용합니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipmentService {

    /** ?sort= 로 정렬할 수 있는 필드 → SQL 컬럼. 기본은 externalId 오름차순 */
    private static final Map<String, String> SORTABLE = Map.of(
            "externalId", "e.external_id",
            "infoUpdatedAt", "e.info_updated_at");

    private final EquipmentRepository equipmentRepository;
    private final EquipmentSpecRepository specRepository;
    private final EquipmentTestTypeRepository testTypeLinkRepository;
    private final EquipmentJdbcRepository jdbcRepository;
    private final OrganizationService organizationService;

    /** null은 기관 미확인, false는 불가 또는 인정 만료. */
    public Boolean accredited(Long equipmentId, String testType) {
        return testTypeLinkRepository.findById(new EquipmentTestType.Key(equipmentId, testType))
                .map(t -> t.accreditedOn(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))).orElse(null);
    }

    /** GET /equipment. q 가 있으면 이름이 비슷한 순서가 먼저, 그다음 sort (기본 externalId) */
    public PageResponse<EquipmentSummaryResponse> search(EquipmentSearchCondition condition, Pageable pageable) {
        Page<Long> ids = jdbcRepository.search(condition, pageable, orderBy(pageable.getSort()));
        List<EquipmentSummaryResponse> content = summaries(ids.getContent());
        return new PageResponse<>(content, ids.getNumber(), ids.getSize(), ids.getTotalElements(), ids.getTotalPages());
    }

    /** GET /equipment/{id}. 없거나 비활성이면 404 EQUIPMENT_NOT_FOUND */
    public EquipmentDetailResponse getDetail(Long id) {
        Equipment e = equipmentRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EQUIPMENT_NOT_FOUND));
        List<EquipmentDetailResponse.Spec> specs = orderRealFirst(specRepository.findAllByEquipmentIdOrderByIdAsc(id))
                .stream().map(EquipmentDetailResponse.Spec::from).toList();
        return EquipmentDetailResponse.of(e, institutionNames(Set.of(e.getInstitutionId())).get(e.getInstitutionId()),
                testTypeCodes(List.of(id)).getOrDefault(id, List.of()), specs, jdbcRepository.findPresetIds(id));
    }

    /** 장비 id 목록 → 목록 항목 (id 순서 유지, 없는 id 는 빠짐) */
    List<EquipmentSummaryResponse> summaries(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Equipment> byId = equipmentRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Equipment::getId, Function.identity()));
        Map<Long, String> names = institutionNames(byId.values().stream().map(Equipment::getInstitutionId)
                .collect(Collectors.toSet()));
        Map<Long, List<String>> testTypes = testTypeCodes(ids);
        return ids.stream().filter(byId::containsKey).map(byId::get)
                .map(e -> EquipmentSummaryResponse.of(e, names.get(e.getInstitutionId()),
                        testTypes.getOrDefault(e.getId(), List.of())))
                .toList();
    }

    /** 기관 id → 활성 장비 수 */
    public Map<Long, Long> countByInstitution() {
        return jdbcRepository.countByInstitution();
    }

    /** 시험유형 코드 → 활성 장비 수 */
    public Map<String, Long> countByTestType() {
        return jdbcRepository.countByTestType();
    }

    /** 한 기관의 장비 유형별 수 */
    public Map<EquipmentType, Long> countByEquipmentType(Long institutionId) {
        return jdbcRepository.countByEquipmentType(institutionId);
    }

    /** 한 기관의 시험유형별 장비 수 */
    public Map<String, Long> countByTestTypeForInstitution(Long institutionId) {
        return jdbcRepository.countByTestTypeForInstitution(institutionId);
    }

    /** 지역 → 활성 장비 수 (지역 없는 장비 제외) */
    public Map<String, Long> countByRegion() {
        return jdbcRepository.countByRegion();
    }

    /** 장비 유형 → 활성 장비 수 */
    public Map<EquipmentType, Long> countByEquipmentTypeAll() {
        return jdbcRepository.countByEquipmentTypeAll();
    }

    /** 기관별 활성 장비 수와 기관 확인 장비 수 */
    public List<EquipmentJdbcRepository.InstitutionVerificationRow> countVerificationByInstitution() {
        return jdbcRepository.countVerificationByInstitution();
    }

    /** 한 기관의 갱신 필요 장비 수 (기관 확인 전 / 마지막 갱신 days 일 경과) */
    public EquipmentJdbcRepository.NeedsUpdateRow countNeedsUpdate(Long institutionId, int days) {
        return jdbcRepository.countNeedsUpdate(institutionId, days);
    }

    /** 장비에 쓰인 지역 이름 목록 */
    public List<String> findRegions() {
        return jdbcRepository.findRegions();
    }

    /** 한 시험유형을 할 수 있는 기관별 집계 (GET /test-types/{code}) */
    public List<InstitutionTestTypeStat> statsByInstitution(String testTypeCode) {
        return jdbcRepository.statsByInstitution(testTypeCode);
    }

    Map<Long, String> institutionNames(Set<Long> institutionIds) {
        return organizationService.findSummaries(institutionIds).values().stream()
                .collect(Collectors.toMap(OrganizationSummaryResponse::id, OrganizationSummaryResponse::name));
    }

    /** 장비 id → 시험유형 코드 목록 (코드 순) */
    Map<Long, List<String>> testTypeCodes(List<Long> equipmentIds) {
        return testTypeLinkRepository.findAllByEquipmentIdIn(equipmentIds).stream()
                .collect(Collectors.groupingBy(EquipmentTestType::getEquipmentId,
                        Collectors.mapping(EquipmentTestType::getTestTypeCode,
                                Collectors.collectingAndThen(Collectors.toList(),
                                        list -> list.stream().sorted().toList()))));
    }

    /** 사양을 처음 나온 키 순서로 묶고, 같은 키 안에서는 REAL 을 MOCK 보다 앞에 둠 */
    static List<EquipmentSpec> orderRealFirst(List<EquipmentSpec> specs) {
        Map<String, List<EquipmentSpec>> byKey = new LinkedHashMap<>();
        specs.forEach(s -> byKey.computeIfAbsent(s.getSpecKey(), k -> new ArrayList<>()).add(s));
        return byKey.values().stream()
                .flatMap(group -> group.stream()
                        .sorted(Comparator.comparing(s -> s.getSourceType() == SpecSourceType.REAL ? 0 : 1)))
                .toList();
    }

    /** 허용된 정렬만 SQL 로 바꿈. 그 외 필드는 400 VALIDATION_FAILED */
    private static String orderBy(Sort sort) {
        if (sort.isUnsorted()) {
            return "e.external_id ASC";
        }
        String clause = sort.stream().map(order -> {
            String column = SORTABLE.get(order.getProperty());
            if (column == null) {
                String reason = "정렬할 수 없는 필드입니다. 가능: " + String.join(", ", SORTABLE.keySet().stream().sorted().toList());
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, reason,
                        Map.of("fields", List.of(Map.of("field", "sort", "reason", reason))));
            }
            return column + (order.isAscending() ? " ASC" : " DESC");
        }).collect(Collectors.joining(", "));
        return clause + ", e.id ASC";
    }
}
