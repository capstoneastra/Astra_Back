package kr.astra.requirement.dto;

import java.time.OffsetDateTime;
import java.util.List;
import kr.astra.requirement.entity.*;

public record RequirementResponse(Long id, Long chatSessionId, String testTypeCode, String title,
        String purpose, String sampleName, Integer sampleQuantity, RequirementStatus status,
        List<ConditionResponse> conditions, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    public record ConditionResponse(String key, String label, java.math.BigDecimal value,
            java.math.BigDecimal min, java.math.BigDecimal max, String valueText, String unit,
            ConditionStatus status, ConditionSource source, java.math.BigDecimal confidence, String note) {}
}
