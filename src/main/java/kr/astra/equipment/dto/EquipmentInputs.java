package kr.astra.equipment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import kr.astra.equipment.entity.AccessScope;
import kr.astra.equipment.entity.Availability;
import kr.astra.equipment.entity.EquipmentFields;
import kr.astra.equipment.entity.EquipmentType;
import kr.astra.equipment.entity.FeeUnit;

/**
 * 기관 장비 관리 요청 본문 (api-spec §2-1). 한 파일에 모아 둡니다.
 */
public final class EquipmentInputs {

    private EquipmentInputs() {
    }

    /** 이용 조건 (등록·수정 공용, null 이면 바꾸지 않음) */
    public record UsageInput(
            AccessScope accessScope,
            @Size(max = 200) String feeText,
            @Min(0) Integer feeAmount,
            FeeUnit feeUnit,
            @Size(max = 200) String durationText,
            @DecimalMin("0") @DecimalMax("100") BigDecimal utilizationRate,
            Availability availability) {
    }

    /**
     * 실제 사양 한 줄. 단일값 value, 범위 min/max, 숫자가 아니면 valueText 중 하나 이상
     *
     * @param key 단위 없는 snake_case (frequency, max_payload …). 조건 규칙의 specKey 와 같은 키를 써야 매칭에 쓰임
     */
    public record SpecInput(
            @NotBlank @Size(max = 80) @Pattern(regexp = "[a-z][a-z0-9_]*",
                    message = "사양 키는 영문 소문자로 시작하는 snake_case 입니다. (예: max_payload)") String key,
            @NotBlank @Size(max = 100) String label,
            BigDecimal value,
            BigDecimal min,
            BigDecimal max,
            @Size(max = 300) String valueText,
            @Size(max = 30) String unit) {
    }

    /** 가능 시험유형 + 예상 비용(원)·소요일 */
    public record TestTypeInput(
            @NotBlank @Size(max = 50) String code,
            @Min(0) Integer estCostMin,
            @Min(0) Integer estCostMax,
            @Min(0) Integer estDurationDays,
            @Size(max = 300) String note) {
    }

    /**
     * POST /equipment. 기관 담당자는 소속 기관으로 등록되고 institutionId 는 보내지 않아도 됩니다.
     * 관리자는 등록할 기관(institutionId)을 꼭 보냅니다.
     */
    public record CreateRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String model,
            @Size(max = 200) String manufacturer,
            @Size(max = 100) String category,
            @NotNull EquipmentType equipmentType,
            @Size(max = 50) String region,
            String description,
            @Size(max = 500) String imageUrl,
            @Valid UsageInput usage,
            @Valid List<SpecInput> specs,
            @Valid List<TestTypeInput> testTypes,
            Long institutionId) {

        public EquipmentFields fields() {
            UsageInput u = usage == null ? new UsageInput(null, null, null, null, null, null, null) : usage;
            return new EquipmentFields(name.trim(), model, manufacturer, category, equipmentType, region, description,
                    imageUrl, u.accessScope(), u.feeText(), u.feeAmount(), u.feeUnit(), u.durationText(),
                    u.utilizationRate(), u.availability());
        }
    }

    /** PATCH /equipment/{id}: 보낸 값만 수정 (null 은 그대로) */
    public record UpdateRequest(
            @Size(min = 1, max = 200) String name,
            @Size(max = 200) String model,
            @Size(max = 200) String manufacturer,
            @Size(max = 100) String category,
            EquipmentType equipmentType,
            @Size(max = 50) String region,
            String description,
            @Size(max = 500) String imageUrl,
            @Valid UsageInput usage) {

        public EquipmentFields fields() {
            UsageInput u = usage == null ? new UsageInput(null, null, null, null, null, null, null) : usage;
            return new EquipmentFields(name == null ? null : name.trim(), model, manufacturer, category, equipmentType,
                    region, description, imageUrl, u.accessScope(), u.feeText(), u.feeAmount(), u.feeUnit(),
                    u.durationText(), u.utilizationRate(), u.availability());
        }
    }

    /** PUT /equipment/{id}/specs: 실제 사양(REAL) 전체를 이 목록으로 교체 (빈 목록이면 REAL 전부 삭제) */
    public record SpecsRequest(@NotNull @Valid List<SpecInput> specs) {
    }

    /** PUT /equipment/{id}/test-types: 가능 시험유형 전체를 이 목록으로 교체 */
    public record TestTypesRequest(@NotNull @Valid List<TestTypeInput> testTypes) {
    }

    /** POST /admin/equipment/{id}/transfer */
    public record TransferRequest(
            @NotNull Long institutionId,
            @Size(max = 300) String reason) {
    }

    /** POST /equipment/compare: 장비 2~5대 */
    public record CompareRequest(
            @NotNull @Size(min = 2, max = 5, message = "비교할 장비는 2~5대입니다.") List<@NotNull Long> equipmentIds) {
    }
}
