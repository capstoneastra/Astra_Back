package kr.astra.equipment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import kr.astra.equipment.entity.AccessScope;
import kr.astra.equipment.entity.Availability;
import kr.astra.equipment.entity.EquipmentChangeAction;
import kr.astra.equipment.entity.EquipmentType;
import kr.astra.equipment.entity.FeeUnit;
import kr.astra.equipment.entity.SpecSourceType;
import kr.astra.equipment.entity.SpecVerificationStatus;
import kr.astra.equipment.entity.VerificationStatus;

/**
 * 기관 장비 관리·유사/중복/비교 응답 (api-spec §2, §2-1). 한 파일에 모아 둡니다.
 */
public final class EquipmentManageResponses {

    private EquipmentManageResponses() {
    }

    /** POST /equipment → 201 */
    public record Created(Long id, String externalId, VerificationStatus verificationStatus) {
    }

    /** 갱신 필요 사유: 기관 확인 전 / 마지막 갱신 90일 경과 */
    public enum UpdateReason {
        NOT_CONFIRMED,
        OUTDATED
    }

    /**
     * GET /institutions/me/equipment 항목: 목록 항목 + 관리용 정보
     *
     * @param needsUpdate   updateReasons 가 하나라도 있으면 true → 화면에 "정보 갱신 필요"
     * @param updateReasons NOT_CONFIRMED(기관 확인 전), OUTDATED(마지막 갱신 90일 경과)
     */
    public record Managed(
            Long id,
            String externalId,
            String name,
            String model,
            EquipmentType equipmentType,
            List<String> testTypes,
            Availability availability,
            VerificationStatus verificationStatus,
            SpecVerificationStatus specVerificationStatus,
            boolean isMock,
            OffsetDateTime infoUpdatedAt,
            long daysSinceUpdate,
            boolean needsUpdate,
            List<UpdateReason> updateReasons) {
    }

    /**
     * GET /equipment/{id}/change-logs 항목
     *
     * @param changes {"필드": {"before": .., "after": ..}}
     */
    public record ChangeLog(Long id, EquipmentChangeAction action, Actor changedBy, Object changes,
                            OffsetDateTime createdAt) {

        public record Actor(Long id, String name) {
        }
    }

    /**
     * GET /equipment/{id}/similar 항목
     *
     * @param reasons 예) "같은 시험유형: 진동시험", "같은 분류: 검사/시험장비", "이름 유사"
     */
    public record Similar(EquipmentSummaryResponse equipment, List<String> reasons, double nameSimilarity) {
    }

    /** GET /equipment/duplicates 그룹: 같은 지역 + 같은 시험유형에 장비 2대 이상 */
    public record DuplicateGroup(String region, TestTypeRef testType, int equipmentCount, int institutionCount,
                                 List<EquipmentSummaryResponse> equipment) {

        public record TestTypeRef(String code, String nameKo) {
        }
    }

    /**
     * POST /equipment/compare
     *
     * @param equipment    요청 순서대로
     * @param rows         사양 키별 한 줄. values 는 equipment 순서와 같고, 그 장비에 사양이 없으면 null
     * @param usage        장비별 이용 조건 (요금·소요 기간·가동률·가용 상태 등). equipment 순서와 같음
     * @param testType     요청에 testType 을 보냈으면 그 코드, 아니면 null
     * @param usedMockData 비교에 MOCK 사양이 하나라도 쓰였으면 true → DEMO 표시
     */
    public record Comparison(List<EquipmentSummaryResponse> equipment, List<Row> rows, List<Usage> usage,
                             String testType, boolean usedMockData) {

        /**
         * 장비 한 대의 이용 조건 (값이 없으면 null → 화면에 "정보 없음")
         *
         * @param testTypeEstimate 요청에 testType 을 보냈을 때만. 그 장비가 그 시험을 못 하면 supported=false
         */
        public record Usage(Long equipmentId, AccessScope accessScope, String feeText, Integer feeAmount,
                            FeeUnit feeUnit, String durationText, BigDecimal utilizationRate,
                            Availability availability, VerificationStatus verificationStatus,
                            OffsetDateTime infoUpdatedAt, TestTypeEstimate testTypeEstimate) {
        }

        /** 그 시험의 예상 비용(원)·소요일 (equipment_test_types) */
        public record TestTypeEstimate(String code, boolean supported, Integer estCostMin, Integer estCostMax,
                                       Integer estDurationDays) {
        }

        public record Row(String key, String label, List<Cell> values) {
        }

        /** 칸 하나: 같은 키에 REAL 이 있으면 REAL, 없으면 MOCK */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Cell(Long equipmentId, BigDecimal value, BigDecimal min, BigDecimal max, String valueText,
                           String unit, SpecSourceType sourceType) {
        }
    }
}
