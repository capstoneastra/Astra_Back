package kr.astra.testtype.dto;

import java.util.List;
import kr.astra.testtype.entity.TestType;

/** GET /test-types 항목. equipmentCount 는 그 시험을 할 수 있는 활성 장비 수 (0 이어도 포함) */
public record TestTypeSummaryResponse(String code, String nameKo, String description, List<String> keywords,
                                      long equipmentCount) {

    public static TestTypeSummaryResponse of(TestType t, long equipmentCount) {
        return new TestTypeSummaryResponse(t.getCode(), t.getNameKo(), t.getDescription(), t.getKeywords(),
                equipmentCount);
    }
}
