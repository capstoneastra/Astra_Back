package kr.astra.equipment.dto;

import java.util.List;
import kr.astra.requirement.dto.ExtractedQuery;

/**
 * GET /search: 시험항목·자연어 통합 검색 (api-spec §2, 기능 3)
 *
 * @param interpreted 문장을 해석한 결과
 * @param testTypes   해석된 시험유형 (코드·이름)
 * @param equipment   찾은 장비 (목록 항목 형식, 최대 20대)
 */
public record SearchResponse(Interpreted interpreted, List<TestTypeRef> testTypes,
                             List<EquipmentSummaryResponse> equipment) {

    /**
     * @param interpreter KEYWORD(키워드·숫자 해석) 또는 AI(BE-C 추출기)
     * @param regions     문장에 있던 지역 (예: 대전) → 장비 검색 지역 필터로 사용
     */
    public record Interpreted(String interpreter, List<String> testTypes, List<ExtractedQuery.Condition> conditions,
                              List<String> regions) {
    }

    public record TestTypeRef(String code, String nameKo) {
    }
}
