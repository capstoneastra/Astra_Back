package kr.astra.testtype.service;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
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

    /** GET /test-types: 전체 (display_order 순) + 활성 장비 수 */
    public List<TestTypeSummaryResponse> list() {
        Map<String, Long> counts = equipmentService.countByTestType();
        return testTypeRepository.findAllByOrderByDisplayOrderAscCodeAsc().stream()
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

    /** 전체 시험유형 (display_order 순). 다른 도메인 응답에 이름을 붙이거나 정렬할 때 */
    public List<TestType> findAllOrdered() {
        return testTypeRepository.findAllByOrderByDisplayOrderAscCodeAsc();
    }

    /** 코드 → 한글 이름 */
    public Map<String, String> nameMap() {
        return findAllOrdered().stream().collect(Collectors.toMap(TestType::getCode, TestType::getNameKo));
    }
}
