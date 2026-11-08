package kr.astra.requirement.service;

import kr.astra.requirement.dto.ExtractedQuery;

/**
 * [BE-C 구현 → BE-A 사용] 문장 → 시험유형·조건 추출기 (GET /search 자연어 해석, api-spec §2 기능 3).
 * <p>
 * BE-A 는 {@code ObjectProvider<ConditionExtractor>} 로 받아서, 구현이 없으면 키워드·숫자 기반 해석으로 대신합니다.
 * 구현 클래스에 {@code @Service} 를 붙이면 /search 가 자동으로 이 추출기를 씁니다 (응답 interpreted.interpreter = "AI").
 * 조건 키는 test_condition_rules.condition_key 안에서만 뽑아 주세요. 시그니처를 바꾸려면 BE-A 와 먼저 맞춰 주세요.
 */
public interface ConditionExtractor {

    /**
     * @param text 사용자가 입력한 문장 (예: "위성 전장품 15kg, 20~2000Hz 진동시험")
     * @return 추출한 시험유형 코드와 조건. 못 찾으면 빈 목록
     */
    ExtractedQuery extract(String text);
}
