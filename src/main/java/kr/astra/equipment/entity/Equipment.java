package kr.astra.equipment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import kr.astra.global.entity.BaseTimeEntity;
import kr.astra.simulation.entity.SimulationCapability;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * equipment: 시험장비. 소속 기관은 id 로만 들고 있습니다 (기관 정보는 OrganizationService 로 조회).
 * 공개자료로 모은 초기 데이터(ASTRA-CAND-xxxx)는 사양 대부분이 시연용 MOCK 이라 is_mock=true.
 */
@Getter
@Entity
@Table(name = "equipment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Equipment extends BaseTimeEntity {

    /** 일반 정보·가용 상태를 이 기간(일) 동안 확인하지 않으면 "갱신 필요" (목록·대시보드·알림 공통) */
    public static final int UPDATE_INTERVAL_DAYS = 90;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true, length = 30)
    private String externalId;

    @Column(name = "institution_id", nullable = false)
    private Long institutionId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 200)
    private String model;

    @Column(length = 200)
    private String manufacturer;

    @Column(length = 100)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "equipment_type", nullable = false, length = 30)
    private EquipmentType equipmentType;

    @Column(length = 50)
    private String region;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    // 이용 조건
    @Enumerated(EnumType.STRING)
    @Column(name = "access_scope", length = 30)
    private AccessScope accessScope;

    @Column(name = "fee_text", length = 200)
    private String feeText;

    @Column(name = "fee_amount")
    private Integer feeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_unit", length = 20)
    private FeeUnit feeUnit;

    /** 이용료 부가세 포함 여부 (V17). 금액이 없는 '협의' 장비는 null */
    @Column(name = "fee_vat_included")
    private Boolean feeVatIncluded;

    @Column(name = "duration_text", length = 200)
    private String durationText;

    @Column(name = "utilization_rate", precision = 5, scale = 2)
    private BigDecimal utilizationRate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Availability availability;

    @Column(name = "availability_updated_at")
    private OffsetDateTime availabilityUpdatedAt;
    @Column(name = "availability_updated_by")
    private Long availabilityUpdatedBy;
    @Column(name = "availability_note", length = 500)
    private String availabilityNote;
    @Column(name = "availability_reminded_at")
    private OffsetDateTime availabilityRemindedAt;
    @Column(name = "lead_time_days")
    private Integer leadTimeDays;
    @Enumerated(EnumType.STRING) @Column(name = "operation_mode", nullable = false, length = 30)
    private OperationMode operationMode = OperationMode.UNKNOWN;

    public boolean availabilityNeedsConfirmation(OffsetDateTime now) {
        return availability == Availability.UNKNOWN || availabilityUpdatedAt == null
                || availabilityUpdatedAt.plusDays(UPDATE_INTERVAL_DAYS).isBefore(now) || availabilityUpdatedBy == null;
    }

    public void updateUsage(kr.astra.equipment.dto.EquipmentInputs.UsageInput input, Long actor, OffsetDateTime now) {
        if (input == null) return;
        if (input.availability() != null) {
            availability = input.availability();
            availabilityUpdatedAt = now;
            availabilityUpdatedBy = actor;
            availabilityNote = input.availabilityNote();
            availabilityRemindedAt = null;
        } else if (input.availabilityNote() != null) {
            availabilityNote = input.availabilityNote();
        }
        if (input.leadTimeDays() != null) leadTimeDays = input.leadTimeDays();
        if (input.operationMode() != null) operationMode = input.operationMode();
    }

    public void markAvailabilityReminded(OffsetDateTime now) { availabilityRemindedAt = now; }

    // 출처 / 검증
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 20)
    private EquipmentSourceType sourceType;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    @Column(name = "source_file", length = 200)
    private String sourceFile;

    @Column(name = "source_page")
    private Integer sourcePage;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 30)
    private VerificationStatus verificationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "spec_verification_status", nullable = false, length = 30)
    private SpecVerificationStatus specVerificationStatus;

    @Column(name = "aerospace_relevance", nullable = false, length = 30)
    private String aerospaceRelevance;

    @Column(name = "is_mock", nullable = false)
    private boolean mock;

    // 시뮬레이션 (기능 15)
    @Enumerated(EnumType.STRING)
    @Column(name = "simulation_capability", length = 30)
    private SimulationCapability simulationCapability;

    @Column(name = "info_updated_at", nullable = false)
    private OffsetDateTime infoUpdatedAt;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    /** 기관 신규 등록에 붙이는 외부 id 형식 (id 4자리): ASTRA-EQ-0072 */
    public static String institutionExternalId(Long id) {
        return "ASTRA-EQ-%04d".formatted(id);
    }

    /**
     * 기관 담당자의 신규 등록. 기관이 직접 입력한 정보라 바로 INSTITUTION_VERIFIED, 시연용 데이터 아님.
     * 외부 id 는 저장 후 id 가 정해지면 {@link #assignExternalId()} 로 붙입니다 (그전에는 임시값).
     */
    public static Equipment registerByInstitution(Long institutionId, Long userId, String temporaryExternalId,
                                                  EquipmentFields fields, OffsetDateTime now) {
        Equipment e = new Equipment();
        e.externalId = temporaryExternalId;
        e.institutionId = institutionId;
        e.availability = Availability.UNKNOWN;
        e.sourceType = EquipmentSourceType.INSTITUTION;
        e.verificationStatus = VerificationStatus.INSTITUTION_VERIFIED;
        e.specVerificationStatus = SpecVerificationStatus.NOT_VERIFIED;
        e.aerospaceRelevance = "NOT_ASSESSED";
        e.mock = false;
        e.active = true;
        e.createdBy = userId;
        e.updatedBy = userId;
        e.infoUpdatedAt = now;
        e.apply(fields);
        return e;
    }

    public void assignExternalId() {
        this.externalId = institutionExternalId(id);
    }

    /** 값이 null 이 아닌 필드만 바꿉니다 (PATCH) */
    public void apply(EquipmentFields f) {
        if (f.name() != null) name = f.name();
        if (f.model() != null) model = f.model();
        if (f.manufacturer() != null) manufacturer = f.manufacturer();
        if (f.category() != null) category = f.category();
        if (f.equipmentType() != null) equipmentType = f.equipmentType();
        if (f.region() != null) region = f.region();
        if (f.description() != null) description = f.description();
        if (f.imageUrl() != null) imageUrl = f.imageUrl();
        if (f.accessScope() != null) accessScope = f.accessScope();
        if (f.feeText() != null) feeText = f.feeText();
        if (f.feeAmount() != null) feeAmount = f.feeAmount();
        if (f.feeUnit() != null) feeUnit = f.feeUnit();
        if (f.feeVatIncluded() != null) feeVatIncluded = f.feeVatIncluded();
        // 문구가 아직 없는 장비에 금액·단위·부가세만 보내면 서버가 "금액원/단위 (부가세 별도)" 형식으로 만듦
        // (이미 있는 문구는 PATCH 규칙대로 보낸 경우에만 바뀜)
        if ((feeText == null || feeText.isBlank())
                && (f.feeAmount() != null || f.feeUnit() != null || f.feeVatIncluded() != null)) {
            feeText = FeeUnit.format(feeAmount, feeUnit, feeVatIncluded);
        }
        // 이용료 정보를 하나도 안 보낸 장비는 시드 장비와 같은 '협의' 형식으로 (V17 규칙: 금액 없음 = 협의·NEGOTIABLE)
        if ((feeText == null || feeText.isBlank()) && feeAmount == null) {
            feeText = FeeUnit.NEGOTIABLE.label();
            feeUnit = FeeUnit.NEGOTIABLE;
            feeVatIncluded = null;
        }
        if (f.durationText() != null) durationText = f.durationText();
        if (f.utilizationRate() != null) utilizationRate = f.utilizationRate();
        if (f.availability() != null) availability = f.availability();
    }

    /** 기관 담당자가 수정·확인함: INSTITUTION_VERIFIED + 정보 갱신일 */
    public void markVerifiedByInstitution(Long userId, OffsetDateTime now) {
        this.verificationStatus = VerificationStatus.INSTITUTION_VERIFIED;
        this.infoUpdatedAt = now;
        this.updatedBy = userId;
    }

    public void touch(Long userId) {
        this.updatedBy = userId;
    }

    public void changeSpecVerificationStatus(SpecVerificationStatus status) {
        this.specVerificationStatus = status;
    }

    public void deactivate(Long userId) {
        this.active = false;
        this.updatedBy = userId;
    }

    /** 관리자: 잘못 수집된 장비의 소속 기관 변경 */
    public void transferTo(Long institutionId, Long adminId) {
        this.institutionId = institutionId;
        this.updatedBy = adminId;
    }
}
