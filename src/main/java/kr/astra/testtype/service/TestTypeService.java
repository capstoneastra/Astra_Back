package kr.astra.testtype.service;

import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kr.astra.equipment.dto.InstitutionTestTypeStat;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.organization.dto.OrganizationSummaryResponse;
import kr.astra.organization.service.OrganizationService;
import kr.astra.testtype.dto.ConditionRuleResponse;
import kr.astra.testtype.dto.TestTypeDetailResponse;
import kr.astra.testtype.dto.TestTypeSummaryResponse;
import kr.astra.testtype.entity.TestType;
import kr.astra.testtype.repository.TestConditionRuleRepository;
import kr.astra.testtype.repository.TestTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시험유형·시험 정보 안내 (api-spec §2, 기능 3·4).
 * 조건 규칙(test_condition_rules)은 BE-C 매칭에서도 {@link #getConditionRules(String)} 로 조회합니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TestTypeService {

    private static final Collator KOREAN = Collator.getInstance(Locale.KOREAN);

    private final TestTypeRepository testTypeRepository;
    private final TestConditionRuleRepository ruleRepository;
    private final EquipmentService equipmentService;
    private final OrganizationService organizationService;
    private final kr.astra.equipment.service.SpecDefinitionService specDefinitions;
    private final kr.astra.testtype.repository.ConditionAliasQuery aliases;

    // 정적 테이블 캐시 (요청마다 같은 15행을 여러 번 읽지 않도록)
    private volatile List<TestType> orderedCache;
    private volatile Map<String, String> nameCache;

    public List<kr.astra.equipment.service.SpecDefinitionService.Definition> specDefinitions() { return specDefinitions.list(); }
    public Map<String,List<String>> conditionAliases(String code) { return aliases.forType(code); }

    /** GET /test-types: 전체 (display_order 순) + 활성 장비 수 */
    public List<TestTypeSummaryResponse> list() {
        Map<String, Long> counts = equipmentService.countByTestType();
        return findAllOrdered().stream()
                .map(t -> TestTypeSummaryResponse.of(t, counts.getOrDefault(t.getCode(), 0L)))
                .toList();
    }

    /** GET /test-types/{code}. 없는 코드면 404 NOT_FOUND */
    public TestTypeDetailResponse getDetail(String code) {
        TestType type = testTypeRepository.findById(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "시험유형을 찾을 수 없습니다.", null));
        List<InstitutionTestTypeStat> stats = equipmentService.statsByInstitution(code);
        Map<Long, OrganizationSummaryResponse> orgs = organizationService.findSummaries(
                stats.stream().map(InstitutionTestTypeStat::institutionId).toList());
        List<TestTypeDetailResponse.Institution> institutions = stats.stream()
                .map(s -> {
                    OrganizationSummaryResponse org = orgs.get(s.institutionId());
                    return new TestTypeDetailResponse.Institution(s.institutionId(),
                            org == null ? null : org.name(), org == null ? null : org.region(), s.equipmentCount(),
                            s.estCostMin(), s.estCostMax(), s.estDurationDays(), s.accessScope());
                })
                .sorted(Comparator.comparingLong(TestTypeDetailResponse.Institution::equipmentCount).reversed()
                        .thenComparing(i -> i.name() == null ? "" : i.name(), KOREAN))
                .toList();
        return new TestTypeDetailResponse(type.getCode(), type.getNameKo(), type.getDescription(),
                getConditionRules(code), institutions);
    }

    /** [BE-C 매칭·누락 분석에서도 사용] 시험유형의 조건 규칙 (display_order 순). 없는 코드면 빈 목록 */
    public List<ConditionRuleResponse> getConditionRules(String testTypeCode) {
        return ruleRepository.findAllByTestTypeCodeOrderByDisplayOrderAscConditionKeyAsc(testTypeCode).stream()
                .map(ConditionRuleResponse::from)
                .toList();
    }

    /**
     * 전체 시험유형 (display_order 순). 다른 도메인 응답에 이름을 붙이거나 정렬할 때.
     * test_types 는 마이그레이션으로만 바뀌어(관리 API 없음) 처음 읽은 목록을 메모리에 두고 씁니다.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<TestType> findAllOrdered() {
        List<TestType> cached = orderedCache;
        if (cached == null) {
            cached = List.copyOf(testTypeRepository.findAllByOrderByDisplayOrderAscCodeAsc());
            orderedCache = cached;
        }
        return cached;
    }

    /** 코드 → 한글 이름 (display_order 순, 수정 불가) */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Map<String, String> nameMap() {
        Map<String, String> cached = nameCache;
        if (cached == null) {
            Map<String, String> names = new LinkedHashMap<>();
            findAllOrdered().forEach(t -> names.put(t.getCode(), t.getNameKo()));
            cached = Collections.unmodifiableMap(names);
            nameCache = cached;
        }
        return cached;
    }
}
