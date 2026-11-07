package kr.astra.testtype.dto;

import kr.astra.requirement.entity.ConditionComparator;
import kr.astra.requirement.entity.ConditionValueType;
import kr.astra.testtype.entity.TestConditionRule;

/**
 * 시험유형의 조건 규칙 한 줄 (GET /test-types/{code} 의 conditionRules, BE-C 매칭에서도 사용).
 *
 * @param conditionKey 사용자가 입력하는 조건 키 (sample_mass …)
 * @param unit         기준 단위. 비교 전에 이 단위로 환산
 * @param isRequired   true 인데 값이 없으면 AI 분석이 MISSING 처리
 * @param specKey      비교할 장비 사양 키 (null 이면 비교하지 않고 기관 확인)
 * @param comparator   RANGE_WITHIN / MAX_LIMIT / MIN_LIMIT / NONE
 */
public record ConditionRuleResponse(String conditionKey, String label, ConditionValueType valueType, String unit,
                                    boolean isRequired, String specKey, ConditionComparator comparator) {

    public static ConditionRuleResponse from(TestConditionRule r) {
        return new ConditionRuleResponse(r.getConditionKey(), r.getLabel(), r.getValueType(), r.getUnit(),
                r.isRequired(), r.getSpecKey(), r.getComparator());
    }
}
