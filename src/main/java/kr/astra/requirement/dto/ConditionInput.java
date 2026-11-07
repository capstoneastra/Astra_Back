package kr.astra.requirement.dto;

import java.math.BigDecimal;
import kr.astra.requirement.entity.ConditionStatus;

public record ConditionInput(String key, BigDecimal value, BigDecimal min, BigDecimal max,
        String valueText, String unit, ConditionStatus status, BigDecimal confidence, String note) {
}
