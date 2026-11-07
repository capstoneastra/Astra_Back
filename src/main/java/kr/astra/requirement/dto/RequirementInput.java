package kr.astra.requirement.dto;

import jakarta.validation.constraints.*;
import java.util.List;
import jakarta.validation.Valid;

public record RequirementInput(@NotBlank @Size(max = 50) String testTypeCode,
        @Size(max = 200) String title, @Size(max = 10000) String purpose,
        @Size(max = 200) String sampleName, @Positive Integer sampleQuantity,
        @Valid @Size(max = 100) List<ConditionInput> conditions) {
}
