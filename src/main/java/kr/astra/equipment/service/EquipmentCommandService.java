package kr.astra.equipment.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.astra.equipment.dto.EquipmentDetailResponse;
import kr.astra.equipment.dto.EquipmentInputs.CreateRequest;
import kr.astra.equipment.dto.EquipmentInputs.SpecInput;
import kr.astra.equipment.dto.EquipmentInputs.TestTypeInput;
import kr.astra.equipment.dto.EquipmentInputs.UpdateRequest;
import kr.astra.equipment.dto.EquipmentManageResponses.ChangeLog;
import kr.astra.equipment.dto.EquipmentManageResponses.Created;
import kr.astra.equipment.dto.EquipmentManageResponses.Managed;
import kr.astra.equipment.dto.EquipmentManageResponses.UpdateReason;
import kr.astra.equipment.entity.Equipment;
import kr.astra.equipment.entity.EquipmentChangeAction;
import kr.astra.equipment.entity.EquipmentChangeLog;
import kr.astra.equipment.entity.EquipmentSpec;
import kr.astra.equipment.entity.EquipmentTestType;
import kr.astra.equipment.entity.EquipmentType;
import kr.astra.equipment.entity.SpecSourceType;
import kr.astra.equipment.entity.SpecVerificationStatus;
import kr.astra.equipment.entity.VerificationStatus;
import kr.astra.equipment.repository.EquipmentChangeLogRepository;
import kr.astra.equipment.repository.EquipmentRepository;
import kr.astra.equipment.repository.EquipmentSpecRepository;
import kr.astra.equipment.repository.EquipmentTestTypeRepository;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.response.PageResponse;
import kr.astra.global.security.AccessGuard;
import kr.astra.global.security.CurrentUser;
import kr.astra.organization.dto.InstitutionInfo;
import kr.astra.organization.service.OrganizationService;
import kr.astra.testtype.entity.TestType;
import kr.astra.testtype.service.TestTypeService;
import kr.astra.user.entity.UserRole;
import kr.astra.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기관 장비 관리 (api-spec §2-1): 기관 담당자는 소속 기관 장비만, 관리자는 전부.
 * 모든 변경은 equipment_change_logs 에 action 과 {"필드": {"before", "after"}} 로 남깁니다.
 * 기관이 수정하거나 확인하면 INSTITUTION_VERIFIED + 정보 갱신일(infoUpdatedAt)을 지금으로 바꿉니다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EquipmentCommandService {

    /** 마지막 갱신 후 이 기간이 지나면 "정보 갱신 필요" */
    static final Duration UPDATE_INTERVAL = Duration.ofDays(90);

    private final EquipmentRepository equipmentRepository;
    private final EquipmentSpecRepository specRepository;
    private final EquipmentTestTypeRepository testTypeLinkRepository;
    private final EquipmentChangeLogRepository changeLogRepository;
    private final EquipmentService equipmentService;
    private final OrganizationService organizationService;
    private final TestTypeService testTypeService;
    private final UserService userService;
    private final JsonMapper jsonMapper;
    private final SpecDefinitionService specDefinitions;

    // ------------------------------------------------------------ 조회

    /**
     * GET /institutions/me/equipment. 기관 담당자는 소속 기관, 관리자는 institutionId 를 꼭 보냄.
     * 기본 정렬 externalId 오름차순.
     */
    @Transactional(readOnly = true)
    public PageResponse<Managed> myEquipment(CurrentUser user, Long institutionId, Pageable pageable) {
        Long target = resolveInstitution(user, institutionId, "institutionId");
        Pageable sorted = pageable.getSort().isSorted() ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by("externalId"));
        Page<Equipment> page = equipmentRepository.findAllByInstitutionIdAndActiveTrue(target, sorted);
        Map<Long, List<String>> testTypes = equipmentService.testTypeCodes(
                page.getContent().stream().map(Equipment::getId).toList());
        OffsetDateTime now = OffsetDateTime.now();
        return PageResponse.from(page, e -> toManaged(e, testTypes.getOrDefault(e.getId(), List.of()), now));
    }

    /** GET /equipment/{id}/change-logs (최신순). 비활성 장비의 이력도 볼 수 있음 */
    @Transactional(readOnly = true)
    public PageResponse<ChangeLog> changeLogs(CurrentUser user, Long equipmentId, Pageable pageable) {
        Equipment e = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EQUIPMENT_NOT_FOUND));
        AccessGuard.requireInstitution(user, e.getInstitutionId());
        Page<EquipmentChangeLog> logs = changeLogRepository.findAllByEquipmentIdOrderByIdDesc(equipmentId,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        Map<Long, String> names = userService.findNames(
                logs.getContent().stream().map(EquipmentChangeLog::getChangedBy).collect(Collectors.toSet()));
        return PageResponse.from(logs, l -> new ChangeLog(l.getId(), l.getAction(),
                new ChangeLog.Actor(l.getChangedBy(), names.get(l.getChangedBy())), readJson(l.getChanges()),
                l.getCreatedAt()));
    }

    // ------------------------------------------------------------ 등록·수정

    /** POST /equipment → 외부 id ASTRA-EQ-{id 4자리}, INSTITUTION_VERIFIED */
    public Created create(CurrentUser user, CreateRequest request) {
        Long institutionId = resolveInstitution(user, request.institutionId(), "institutionId");
        InstitutionInfo institution = organizationService.findVerifiedInstitution(institutionId).orElseThrow();
        List<SpecInput> specs = request.specs() == null ? List.of() : request.specs();
        List<TestTypeInput> testTypes = request.testTypes() == null ? List.of() : request.testTypes();
        validateSpecs(specs, "specs");
        validateTestTypes(testTypes, "testTypes");
        requireTestType(request.equipmentType(), testTypes.isEmpty());

        OffsetDateTime now = OffsetDateTime.now();
        Equipment e = Equipment.registerByInstitution(institutionId, user.userId(), temporaryExternalId(),
                request.fields(), now);
        e.updateUsage(request.usage(), user.userId(), now);
        if (e.getRegion() == null) {
            e.apply(regionOnly(institution.region()));
        }
        equipmentRepository.saveAndFlush(e);
        e.assignExternalId();
        saveSpecs(e, specs);
        saveTestTypes(e.getId(), testTypes);
        equipmentRepository.flush();

        Map<String, Object> changes = new LinkedHashMap<>();
        snapshot(e).forEach((field, value) -> {
            if (value != null) {
                changes.put(field, change(null, value));
            }
        });
        changes.put("specs", change(null, specSummaries(specRepository.findAllByEquipmentIdOrderByIdAsc(e.getId()))));
        changes.put("testTypes", change(null, testTypeSummaries(e.getId())));
        log(e.getId(), user.userId(), EquipmentChangeAction.CREATE, changes);
        return new Created(e.getId(), e.getExternalId(), e.getVerificationStatus());
    }

    /** PATCH /equipment/{id}: 보낸 값만 수정 → INSTITUTION_VERIFIED, infoUpdatedAt 갱신 */
    public EquipmentDetailResponse update(CurrentUser user, Long equipmentId, UpdateRequest request) {
        Equipment e = findOwned(user, equipmentId);
        Map<String, Object> before = snapshot(e);
        if (request.equipmentType() != null) {
            requireTestType(request.equipmentType(),
                    testTypeLinkRepository.findAllByEquipmentIdOrderByTestTypeCodeAsc(equipmentId).isEmpty());
        }
        e.apply(request.fields());
        e.updateUsage(request.usage(), user.userId(), OffsetDateTime.now());
        e.markVerifiedByInstitution(user.userId(), OffsetDateTime.now());
        log(e.getId(), user.userId(), EquipmentChangeAction.UPDATE, diff(before, snapshot(e)));
        equipmentRepository.flush();
        return equipmentService.getDetail(equipmentId);
    }

    /** PUT /equipment/{id}/specs: REAL 사양만 통째로 교체 (MOCK 은 그대로) → 사양 검증 상태 VERIFIED */
    public EquipmentDetailResponse replaceSpecs(CurrentUser user, Long equipmentId, List<SpecInput> specs) {
        Equipment e = findOwned(user, equipmentId);
        validateSpecs(specs, "specs");
        List<Map<String, Object>> before = specSummaries(
                specRepository.findAllByEquipmentIdAndSourceTypeOrderByIdAsc(equipmentId, SpecSourceType.REAL));
        String beforeSpecStatus = e.getSpecVerificationStatus().name();
        VerificationStatus beforeStatus = e.getVerificationStatus();

        specRepository.deleteByEquipmentIdAndSourceType(equipmentId, SpecSourceType.REAL);
        e = equipmentRepository.findById(equipmentId).orElseThrow(); // delete 쿼리 후 영속성 컨텍스트가 비워짐
        saveSpecs(e, specs);
        e.changeSpecVerificationStatus(specs.isEmpty() ? SpecVerificationStatus.NOT_VERIFIED
                : SpecVerificationStatus.VERIFIED);
        e.markVerifiedByInstitution(user.userId(), OffsetDateTime.now());

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("specs", change(before, specSummaries(
                specRepository.findAllByEquipmentIdAndSourceTypeOrderByIdAsc(equipmentId, SpecSourceType.REAL))));
        putIfChanged(changes, "specVerificationStatus", beforeSpecStatus, e.getSpecVerificationStatus().name());
        putIfChanged(changes, "verificationStatus", beforeStatus.name(), e.getVerificationStatus().name());
        log(equipmentId, user.userId(), EquipmentChangeAction.SPECS_UPDATE, changes);
        equipmentRepository.flush();
        return equipmentService.getDetail(equipmentId);
    }

    /** PUT /equipment/{id}/test-types: 가능 시험유형 전체 교체 */
    public EquipmentDetailResponse replaceTestTypes(CurrentUser user, Long equipmentId, List<TestTypeInput> testTypes) {
        Equipment e = findOwned(user, equipmentId);
        validateTestTypes(testTypes, "testTypes");
        requireTestType(e.getEquipmentType(), testTypes.isEmpty());
        List<Map<String, Object>> before = testTypeSummaries(equipmentId);
        VerificationStatus beforeStatus = e.getVerificationStatus();

        testTypeLinkRepository.deleteByEquipmentId(equipmentId);
        e = equipmentRepository.findById(equipmentId).orElseThrow();
        saveTestTypes(equipmentId, testTypes);
        e.markVerifiedByInstitution(user.userId(), OffsetDateTime.now());
        testTypeLinkRepository.flush();

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("testTypes", change(before, testTypeSummaries(equipmentId)));
        putIfChanged(changes, "verificationStatus", beforeStatus.name(), e.getVerificationStatus().name());
        log(equipmentId, user.userId(), EquipmentChangeAction.TEST_TYPES_UPDATE, changes);
        equipmentRepository.flush();
        return equipmentService.getDetail(equipmentId);
    }

    /** POST /equipment/{id}/confirm: 정보가 맞다는 확인만 → INSTITUTION_VERIFIED, infoUpdatedAt=now */
    public EquipmentDetailResponse confirm(CurrentUser user, Long equipmentId) {
        Equipment e = findOwned(user, equipmentId);
        Map<String, Object> changes = new LinkedHashMap<>();
        VerificationStatus beforeStatus = e.getVerificationStatus();
        OffsetDateTime beforeUpdatedAt = e.getInfoUpdatedAt();
        e.markVerifiedByInstitution(user.userId(), OffsetDateTime.now());
        changes.put("verificationStatus", change(beforeStatus.name(), e.getVerificationStatus().name()));
        changes.put("infoUpdatedAt", change(beforeUpdatedAt.toString(), e.getInfoUpdatedAt().toString()));
        log(equipmentId, user.userId(), EquipmentChangeAction.CONFIRM, changes);
        equipmentRepository.flush();
        return equipmentService.getDetail(equipmentId);
    }

    /** DELETE /equipment/{id}: 비활성화 (기존 의뢰 이력 보존). 이후 목록·상세에서 안 보임 */
    public void deactivate(CurrentUser user, Long equipmentId) {
        Equipment e = findOwned(user, equipmentId);
        e.deactivate(user.userId());
        log(equipmentId, user.userId(), EquipmentChangeAction.DEACTIVATE, Map.of("active", change(true, false)));
    }

    /** POST /admin/equipment/{id}/transfer: 관리자만, 승인된 다른 기관으로 소속 변경 */
    public EquipmentDetailResponse transfer(CurrentUser admin, Long equipmentId, Long targetInstitutionId,
                                            String reason) {
        AccessGuard.requireAdmin(admin);
        Equipment e = equipmentRepository.findByIdAndActiveTrue(equipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EQUIPMENT_NOT_FOUND));
        if (organizationService.findVerifiedInstitution(targetInstitutionId).isEmpty()) {
            throw invalidField("institutionId", "승인된 기관만 선택할 수 있습니다.");
        }
        if (targetInstitutionId.equals(e.getInstitutionId())) {
            throw invalidField("institutionId", "이미 그 기관 소속입니다.");
        }
        Long before = e.getInstitutionId();
        e.transferTo(targetInstitutionId, admin.userId());
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("institutionId", change(before, targetInstitutionId));
        if (reason != null && !reason.isBlank()) {
            changes.put("reason", reason.trim());
        }
        log(equipmentId, admin.userId(), EquipmentChangeAction.TRANSFER, changes);
        equipmentRepository.flush();
        return equipmentService.getDetail(equipmentId);
    }

    // ------------------------------------------------------------ 내부

    /**
     * 작업할 기관 id. 기관 담당자는 소속 기관 (다른 기관을 보내면 403), 관리자는 요청 값 필수 (승인된 기관).
     * 기업 회원은 403.
     */
    private Long resolveInstitution(CurrentUser user, Long requested, String field) {
        AccessGuard.requireRole(user, UserRole.INSTITUTION_MANAGER);
        if (user.isAdmin()) {
            if (requested == null) {
                throw invalidField(field, "관리자는 대상 기관(" + field + ")을 지정해야 합니다.");
            }
            if (organizationService.findVerifiedInstitution(requested).isEmpty()) {
                throw invalidField(field, "승인된 기관만 선택할 수 있습니다.");
            }
            return requested;
        }
        if (requested != null && !requested.equals(user.organizationId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return user.organizationId();
    }

    /** 활성 장비 + 소유 기관 확인 (없으면 404, 다른 기관이면 403) */
    private Equipment findOwned(CurrentUser user, Long equipmentId) {
        Equipment e = equipmentRepository.lockActive(equipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EQUIPMENT_NOT_FOUND));
        AccessGuard.requireInstitution(user, e.getInstitutionId());
        return e;
    }

    static Managed toManaged(Equipment e, List<String> testTypes, OffsetDateTime now) {
        List<UpdateReason> reasons = new ArrayList<>();
        if (e.getVerificationStatus() != VerificationStatus.INSTITUTION_VERIFIED) {
            reasons.add(UpdateReason.NOT_CONFIRMED);
        }
        if (e.getInfoUpdatedAt().plus(UPDATE_INTERVAL).isBefore(now)) {
            reasons.add(UpdateReason.OUTDATED);
        }
        long days = Math.max(0, Duration.between(e.getInfoUpdatedAt(), now).toDays());
        return new Managed(e.getId(), e.getExternalId(), e.getName(), e.getModel(), e.getEquipmentType(), testTypes,
                e.getAvailability(), e.getVerificationStatus(), e.getSpecVerificationStatus(), e.isMock(),
                e.getInfoUpdatedAt(), days, !reasons.isEmpty() || e.availabilityNeedsConfirmation(now), reasons,
                e.availabilityNeedsConfirmation(now));
    }

    private void validateSpecs(List<SpecInput> specs, String path) {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < specs.size(); i++) {
            SpecInput s = specs.get(i);
            String at = path + "[" + i + "]";
            if (s.value() == null && s.min() == null && s.max() == null
                    && (s.valueText() == null || s.valueText().isBlank())) {
                throw invalidField(at + ".value", "value, min/max, valueText 중 하나는 입력해 주세요.");
            }
            if (s.min() != null && s.max() != null && s.min().compareTo(s.max()) > 0) {
                throw invalidField(at + ".min", "min 이 max 보다 클 수 없습니다.");
            }
            if (!keys.add(s.key())) {
                throw invalidField(at + ".key", "같은 사양 키가 두 번 있습니다: " + s.key());
            }
            try { specDefinitions.normalize(s); }
            catch (BusinessException ex) { throw invalidField(at + ".key", ex.getMessage()); }
        }
    }

    private void requireTestType(EquipmentType type, boolean empty) {
        if (empty && (type == EquipmentType.TEST || type == EquipmentType.MEASUREMENT)) {
            throw invalidField("testTypes", "시험·측정 장비는 시험유형이 하나 이상 필요합니다.");
        }
    }

    private void validateTestTypes(List<TestTypeInput> testTypes, String path) {
        Set<String> known = testTypeService.findAllOrdered().stream().map(TestType::getCode)
                .collect(Collectors.toSet());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < testTypes.size(); i++) {
            TestTypeInput t = testTypes.get(i);
            if (Boolean.TRUE.equals(t.isAccredited()) && (t.accreditationNo() == null
                    || t.accreditationNo().isBlank() || t.accreditationValidUntil() == null)) {
                throw invalidField(path + "[" + i + "].isAccredited", "공인 여부에는 인정번호와 유효기간이 필요합니다.");
            }
            String at = path + "[" + i + "]";
            if (!known.contains(t.code())) {
                throw invalidField(at + ".code", "없는 시험유형 코드입니다: " + t.code());
            }
            if (!seen.add(t.code())) {
                throw invalidField(at + ".code", "같은 시험유형이 두 번 있습니다: " + t.code());
            }
            if (t.estCostMin() != null && t.estCostMax() != null && t.estCostMin() > t.estCostMax()) {
                throw invalidField(at + ".estCostMin", "estCostMin 이 estCostMax 보다 클 수 없습니다.");
            }
        }
    }

    private void saveSpecs(Equipment e, List<SpecInput> specs) {
        specRepository.saveAll(specs.stream()
                .map(input -> {
                    SpecInput s = specDefinitions.normalize(input);
                    EquipmentSpec spec = EquipmentSpec.real(e.getId(), s.key(), s.label(), s.value(), s.min(), s.max(), s.valueText(), s.unit());
                    spec.preserveOriginalUnit(input.unit());
                    return spec;
                })
                .toList());
        specRepository.flush();
    }

    private void saveTestTypes(Long equipmentId, List<TestTypeInput> testTypes) {
        testTypeLinkRepository.saveAll(testTypes.stream()
                .map(t -> {
                    EquipmentTestType link = EquipmentTestType.of(equipmentId, t.code(), t.estCostMin(), t.estCostMax(), t.estDurationDays(), t.note());
                    link.accreditation(t.isAccredited(), t.accreditationNo(), t.accreditationValidUntil());
                    return link;
                })
                .toList());
        testTypeLinkRepository.flush();
    }

    /** 변경 이력에 비교하는 장비 필드 (enum 은 이름, 나머지는 값 그대로) */
    private static Map<String, Object> snapshot(Equipment e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.getName());
        m.put("model", e.getModel());
        m.put("manufacturer", e.getManufacturer());
        m.put("category", e.getCategory());
        m.put("equipmentType", name(e.getEquipmentType()));
        m.put("region", e.getRegion());
        m.put("description", e.getDescription());
        m.put("imageUrl", e.getImageUrl());
        m.put("accessScope", name(e.getAccessScope()));
        m.put("feeText", e.getFeeText());
        m.put("feeAmount", e.getFeeAmount());
        m.put("feeUnit", name(e.getFeeUnit()));
        m.put("durationText", e.getDurationText());
        m.put("utilizationRate", e.getUtilizationRate());
        m.put("availability", name(e.getAvailability()));
        m.put("availabilityUpdatedAt", e.getAvailabilityUpdatedAt());
        m.put("availabilityUpdatedBy", e.getAvailabilityUpdatedBy());
        m.put("availabilityNote", e.getAvailabilityNote());
        m.put("leadTimeDays", e.getLeadTimeDays());
        m.put("operationMode", name(e.getOperationMode()));
        m.put("verificationStatus", name(e.getVerificationStatus()));
        return m;
    }

    private static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        before.forEach((field, b) -> putIfChanged(changes, field, b, after.get(field)));
        return changes;
    }

    private static void putIfChanged(Map<String, Object> changes, String field, Object before, Object after) {
        boolean same = (before instanceof java.math.BigDecimal bb && after instanceof java.math.BigDecimal ab)
                ? bb.compareTo(ab) == 0 : Objects.equals(before, after);
        if (!same) {
            changes.put(field, change(before, after));
        }
    }

    private static Map<String, Object> change(Object before, Object after) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("before", before);
        m.put("after", after);
        return m;
    }

    private static List<Map<String, Object>> specSummaries(List<EquipmentSpec> specs) {
        return specs.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", s.getSpecKey());
            m.put("label", s.getLabel());
            if (s.getValueNum() != null) m.put("value", s.getValueNum());
            if (s.getMinNum() != null) m.put("min", s.getMinNum());
            if (s.getMaxNum() != null) m.put("max", s.getMaxNum());
            if (s.getValueText() != null) m.put("valueText", s.getValueText());
            if (s.getUnit() != null) m.put("unit", s.getUnit());
            return m;
        }).toList();
    }

    private List<Map<String, Object>> testTypeSummaries(Long equipmentId) {
        return testTypeLinkRepository.findAllByEquipmentIdOrderByTestTypeCodeAsc(equipmentId).stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", t.getTestTypeCode());
            m.put("estCostMin", t.getEstCostMin());
            m.put("estCostMax", t.getEstCostMax());
            m.put("estDurationDays", t.getEstDurationDays());
            m.put("isAccredited", t.getAccredited());
            m.put("accreditationNo", t.getAccreditationNo());
            m.put("accreditationValidUntil", t.getAccreditationValidUntil());
            return m;
        }).toList();
    }

    private void log(Long equipmentId, Long userId, EquipmentChangeAction action, Map<String, Object> changes) {
        changeLogRepository.save(new EquipmentChangeLog(equipmentId, userId, action,
                jsonMapper.writeValueAsString(changes)));
    }

    private Object readJson(String json) {
        return json == null ? null : jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    /** 등록 직후 외부 id 를 붙이기 전까지 쓰는 임시값 (UNIQUE, 30자 이내) */
    private static String temporaryExternalId() {
        return "TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private static kr.astra.equipment.entity.EquipmentFields regionOnly(String region) {
        return new kr.astra.equipment.entity.EquipmentFields(null, null, null, null, null, region, null, null, null,
                null, null, null, null, null, null);
    }

    private static BusinessException invalidField(String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, reason,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
