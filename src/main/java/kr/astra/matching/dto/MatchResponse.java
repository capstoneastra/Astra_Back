package kr.astra.matching.dto;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import kr.astra.equipment.dto.EquipmentCandidate;
import kr.astra.matching.entity.*;
public record MatchResponse(Long id, Long requirementId, String algorithmVersion, OffsetDateTime createdAt,
        List<Result> results, String disclaimer) {
    public record Result(Long id, int rank, BigDecimal score, MatchVerdict verdict, boolean usedMockData,
            EquipmentCandidate equipment, String reason, List<Check> checks) {}
    /**
     * @param label      조건 한글 이름 (시료 질량). 화면에는 conditionKey 대신 이것을 보여 줌
     * @param resultText 판정 한글 문구 (충족 / 미충족 / 확인 필요)
     */
    public record Check(String conditionKey, String requiredText, String equipmentText, CheckResult result, String note,
            String label, String resultText) {
        public Check(String conditionKey, String requiredText, String equipmentText, CheckResult result, String note, String label) {
            this(conditionKey, requiredText, equipmentText, result, note, label == null ? conditionKey : label, text(result));
        }
        public static String text(CheckResult result) {
            return switch (result) { case MET -> "충족"; case NOT_MET -> "미충족"; case UNKNOWN -> "확인 필요"; };
        }
    }
}
