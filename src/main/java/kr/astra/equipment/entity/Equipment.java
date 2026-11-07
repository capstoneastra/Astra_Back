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

    @Column(name = "duration_text", length = 200)
    private String durationText;

    @Column(name = "utilization_rate", precision = 5, scale = 2)
    private BigDecimal utilizationRate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Availability availability;

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
}
