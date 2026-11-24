package kr.astra.equipment.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import kr.astra.equipment.entity.AccessScope;
import kr.astra.equipment.entity.Availability;
import kr.astra.equipment.entity.Equipment;
import kr.astra.equipment.entity.EquipmentType;
import kr.astra.equipment.entity.FeeUnit;
import kr.astra.equipment.entity.VerificationStatus;

/**
 * GET /equipment 목록 항목 (api-spec §2 예시 ASTRA-CAND-0048 과 같은 형식).
 *
 * @param testTypes 이 장비로 할 수 있는 시험유형 코드
 * @param isMock    시연용 가상 데이터가 섞인 장비면 true → 화면에 DEMO 표시
 * @param usage     이용 조건 요약 (상세 usage 의 일부: 외부 이용 범위·이용료)
 * @param keySpecs  주요 사양 최대 2개 (상세 specs 순서에서 사양 키가 다른 앞 2개, 같은 키면 REAL 우선)
 * @param simulation 가상 시뮬레이션 지원 (상세 simulation 과 같은 형식)
 */
public record EquipmentSummaryResponse(
        Long id,
        String externalId,
        String name,
        String model,
        InstitutionRef institution,
        String region,
        EquipmentType equipmentType,
        List<String> testTypes,
        Availability availability,
        BigDecimal utilizationRate,
        String feeText,
        VerificationStatus verificationStatus,
        boolean isMock,
        OffsetDateTime infoUpdatedAt,
        boolean availabilityNeedsConfirmation,
        Usage usage,
        List<EquipmentDetailResponse.Spec> keySpecs,
        EquipmentDetailResponse.Simulation simulation) {

    /** 목록에 넣는 주요 사양 수 */
    public static final int KEY_SPEC_LIMIT = 2;

    public static EquipmentSummaryResponse of(Equipment e, String institutionName, List<String> testTypes,
                                              List<EquipmentDetailResponse.Spec> keySpecs, List<String> presetIds) {
        return new EquipmentSummaryResponse(e.getId(), e.getExternalId(), e.getName(), e.getModel(),
                new InstitutionRef(e.getInstitutionId(), institutionName), e.getRegion(), e.getEquipmentType(),
                testTypes, e.getAvailability(), e.getUtilizationRate(), e.getFeeText(), e.getVerificationStatus(),
                e.isMock(), e.getInfoUpdatedAt(), e.availabilityNeedsConfirmation(OffsetDateTime.now()),
                new Usage(e.getAccessScope(), e.getFeeText(), e.getFeeAmount(), e.getFeeUnit(), e.getFeeVatIncluded(),
                        e.getDurationText()),
                keySpecs, new EquipmentDetailResponse.Simulation(e.getSimulationCapability(), presetIds));
    }

    /** 목록용 이용 조건 (값 이름·형식은 상세 usage 와 같음) */
    public record Usage(AccessScope accessScope, String feeText, Integer feeAmount, FeeUnit feeUnit,
                        Boolean vatIncluded, String durationText) {
    }

    /** 소속 기관 (id·이름) */
    public record InstitutionRef(Long id, String name) {
    }
}
