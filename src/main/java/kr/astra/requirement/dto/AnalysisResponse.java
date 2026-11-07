package kr.astra.requirement.dto;
import java.util.List;
public record AnalysisResponse(Long requirementId, String testTypeCode,
        List<RequirementResponse.ConditionResponse> conditions, List<Missing> missing,
        List<Unconfirmed> unconfirmed, boolean readyForMatching) {
    public record Missing(String key, String label, String question) {}
    public record Unconfirmed(String key, String label, String reason) {}
}
