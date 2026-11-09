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
    public record Check(String conditionKey, String requiredText, String equipmentText, CheckResult result, String note) {}
}
