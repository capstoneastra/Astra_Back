package kr.astra.equipment.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.astra.equipment.dto.EquipmentSearchCondition;
import kr.astra.equipment.dto.EquipmentSummaryResponse;
import kr.astra.equipment.dto.SearchResponse;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.requirement.dto.ExtractedQuery;
import kr.astra.requirement.service.ConditionExtractor;
import kr.astra.testtype.service.TestTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시험항목·자연어 통합 검색 (GET /search, api-spec §2 기능 3).
 * 문장 해석은 BE-C 의 {@link ConditionExtractor} 가 있으면 그것(AI), 없으면 {@link KeywordConditionExtractor}(KEYWORD).
 * 장비는 해석된 시험유형으로 찾고, 시험유형을 못 찾으면 문장 그대로 장비명·모델 검색을 합니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchService {

    static final int EQUIPMENT_LIMIT = 20;

    private final ObjectProvider<ConditionExtractor> aiExtractor;
    private final KeywordConditionExtractor keywordExtractor;
    private final EquipmentService equipmentService;
    private final TestTypeService testTypeService;

    public SearchResponse search(String q) {
        if (q == null || q.isBlank()) {
            String reason = "검색어를 입력해 주세요.";
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, reason,
                    Map.of("fields", List.of(Map.of("field", "q", "reason", reason))));
        }
        String text = q.trim();
        ConditionExtractor ai = aiExtractor.getIfAvailable();
        ExtractedQuery extracted = ai != null ? ai.extract(text) : keywordExtractor.extract(text);
        if (extracted == null) {
            extracted = ExtractedQuery.empty();
        }
        List<String> regions = equipmentService.findRegions().stream().filter(text::contains).toList();

        Map<String, String> names = testTypeService.nameMap();
        List<String> codes = extracted.testTypes().stream().filter(names::containsKey).toList();
        List<SearchResponse.TestTypeRef> testTypes = codes.stream()
                .map(code -> new SearchResponse.TestTypeRef(code, names.get(code))).toList();

        return new SearchResponse(
                new SearchResponse.Interpreted(ai != null ? "AI" : "KEYWORD", codes, extracted.conditions(), regions),
                testTypes, findEquipment(text, codes, regions));
    }

    /** 시험유형마다 장비를 찾아 합침(중복 제거, 최대 20대). 시험유형이 없으면 문장으로 장비명·모델 검색 */
    private List<EquipmentSummaryResponse> findEquipment(String text, List<String> codes, List<String> regions) {
        PageRequest page = PageRequest.of(0, EQUIPMENT_LIMIT);
        if (codes.isEmpty()) {
            return equipmentService.search(new EquipmentSearchCondition(text, regions, null, null, null, null, false,
                    true), page).content();
        }
        Map<Long, EquipmentSummaryResponse> merged = new LinkedHashMap<>();
        for (String code : codes) {
            equipmentService.search(new EquipmentSearchCondition(null, regions, null, null, code, null, false, true),
                    page).content().forEach(e -> merged.putIfAbsent(e.id(), e));
        }
        return new ArrayList<>(merged.values()).subList(0, Math.min(EQUIPMENT_LIMIT, merged.size()));
    }
}
