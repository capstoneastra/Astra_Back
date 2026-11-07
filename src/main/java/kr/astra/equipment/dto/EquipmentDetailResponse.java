package kr.astra.equipment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import kr.astra.equipment.dto.EquipmentSummaryResponse.InstitutionRef;
import kr.astra.equipment.entity.AccessScope;
import kr.astra.equipment.entity.Availability;
import kr.astra.equipment.entity.Equipment;
import kr.astra.equipment.entity.EquipmentSourceType;
import kr.astra.equipment.entity.EquipmentSpec;
import kr.astra.equipment.entity.EquipmentType;
import kr.astra.equipment.entity.FeeUnit;
import kr.astra.equipment.entity.SpecSourceType;
import kr.astra.equipment.entity.UsageScope;
import kr.astra.equipment.entity.VerificationStatus;
import kr.astra.simulation.entity.SimulationCapability;

/**
 * GET /equipment/{id}: 목록 항목 필드 + 이용 조건(usage), 사양(specs), 출처(source), 시뮬레이션(simulation).
 * specs 는 REAL·MOCK 을 모두 주고, 같은 사양 키면 REAL 을 먼저 둡니다. MOCK 은 화면에 DEMO 표시.
 */
public record EquipmentDetailResponse(
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
        String manufacturer,
        String category,
        String description,
        Usage usage,
        List<Spec> specs,
        Source source,
        Simulation simulation) {

    public static EquipmentDetailResponse of(Equipment e, String institutionName, List<String> testTypes,
                                             List<Spec> specs, List<String> presetIds) {
        return new EquipmentDetailResponse(e.getId(), e.getExternalId(), e.getName(), e.getModel(),
                new InstitutionRef(e.getInstitutionId(), institutionName), e.getRegion(), e.getEquipmentType(),
                testTypes, e.getAvailability(), e.getUtilizationRate(), e.getFeeText(), e.getVerificationStatus(),
                e.isMock(), e.getInfoUpdatedAt(), e.getManufacturer(), e.getCategory(), e.getDescription(),
                new Usage(e.getAccessScope(), e.getFeeText(), e.getFeeAmount(), e.getFeeUnit(), e.getDurationText(),
                        e.getUtilizationRate(), e.getAvailability()),
                specs,
                new Source(e.getSourceType(), e.getSourceUrl(), e.getSourceFile(), e.getSourcePage()),
                new Simulation(e.getSimulationCapability(), presetIds));
    }

    /** 이용 조건 */
    public record Usage(AccessScope accessScope, String feeText, Integer feeAmount, FeeUnit feeUnit,
                        String durationText, BigDecimal utilizationRate, Availability availability) {
    }

    /**
     * 사양 한 줄. 단일값은 value, 범위는 min/max, 숫자가 아니면 valueText (값이 없는 필드는 응답에서 생략)
     *
     * @param key 사양 키 (frequency, max_payload …). test_condition_rules.spec_key 와 같은 키
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Spec(String key, String label, BigDecimal value, BigDecimal min, BigDecimal max, String valueText,
                       String unit, SpecSourceType sourceType, UsageScope usageScope, String verificationStatus) {

        public static Spec from(EquipmentSpec s) {
            return new Spec(s.getSpecKey(), s.getLabel(), s.getValueNum(), s.getMinNum(), s.getMaxNum(),
                    s.getValueText(), s.getUnit(), s.getSourceType(), s.getUsageScope(), s.getVerificationStatus());
        }
    }

    /** 데이터 출처 (공개자료 수집 위치) */
    public record Source(EquipmentSourceType type, String url, String file, Integer page) {
    }

    /** 가상 시뮬레이션 지원 (전부 DEMO) */
    public record Simulation(SimulationCapability capability, List<String> presetIds) {
    }
}
