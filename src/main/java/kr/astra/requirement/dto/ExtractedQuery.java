package kr.astra.requirement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * 문장에서 뽑은 시험유형·조건 (ConditionExtractor 결과, GET /search 의 interpreted)
 *
 * @param testTypes  시험유형 코드 (예: VIBRATION)
 * @param conditions 조건 (키는 test_condition_rules.condition_key)
 */
public record ExtractedQuery(List<String> testTypes, List<Condition> conditions) {

    public static ExtractedQuery empty() {
        return new ExtractedQuery(List.of(), List.of());
    }

    /**
     * 조건 하나. 단일값 value, 범위 min/max, 숫자가 아니면 valueText (값이 없는 필드는 응답에서 생략)
     *
     * @param key 조건 키 (sample_mass, frequency …)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Condition(String key, BigDecimal value, BigDecimal min, BigDecimal max, String valueText,
                            String unit) {
    }
}
