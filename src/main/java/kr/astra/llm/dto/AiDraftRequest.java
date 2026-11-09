package kr.astra.llm.dto;
import jakarta.validation.constraints.*;
public record AiDraftRequest(@NotNull @Positive Long requirementId, @NotNull @Positive Long equipmentId,
        @Positive Long matchResultId) {}
