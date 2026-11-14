package kr.astra.equipment.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import kr.astra.equipment.entity.Availability;
import kr.astra.equipment.entity.Equipment;
import kr.astra.equipment.entity.EquipmentType;
import kr.astra.equipment.entity.VerificationStatus;

/**
 * GET /equipment 목록 항목 (api-spec §2 예시 ASTRA-CAND-0048 과 같은 형식).
 *
 * @param testTypes 이 장비로 할 수 있는 시험유형 코드
 * @param isMock    시연용 가상 데이터가 섞인 장비면 true → 화면에 DEMO 표시
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
        boolean availabilityNeedsConfirmation) {

    public static EquipmentSummaryResponse of(Equipment e, String institutionName, List<String> testTypes) {
        return new EquipmentSummaryResponse(e.getId(), e.getExternalId(), e.getName(), e.getModel(),
                new InstitutionRef(e.getInstitutionId(), institutionName), e.getRegion(), e.getEquipmentType(),
                testTypes, e.getAvailability(), e.getUtilizationRate(), e.getFeeText(), e.getVerificationStatus(),
                e.isMock(), e.getInfoUpdatedAt(), e.availabilityNeedsConfirmation(OffsetDateTime.now()));
    }

    /** 소속 기관 (id·이름) */
    public record InstitutionRef(Long id, String name) {
    }
}
