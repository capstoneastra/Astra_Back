package kr.astra.aipipeline.dto;
import java.util.List;
import kr.astra.aipipeline.entity.*;
public record PipelineResponse(Long id, PipelineStatus status, PipelineStep currentStep, List<Step> steps,
        Long requirementId, Long matchRunId, Long draftRequestId, String errorMessage) {
    public record Step(PipelineStep step, String status, String message) {}
}
