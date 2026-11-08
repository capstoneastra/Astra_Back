package kr.astra.equipment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.astra.equipment.dto.SearchResponse;
import kr.astra.equipment.service.SearchService;
import kr.astra.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "검색", description = "시험항목·자연어 통합 검색 (api-spec §2, 기능 3)")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @Operation(summary = "시험항목·자연어 통합 검색", description = "문장에서 시험유형·조건·지역을 해석하고 해당 장비를 찾음 (최대 20대). "
            + "BE-C 추출기가 생기기 전까지는 키워드·숫자 해석(interpreter=KEYWORD). 시험유형을 못 찾으면 장비명·모델 검색")
    @GetMapping
    public ApiResponse<SearchResponse> search(
            @Parameter(description = "예: 위성 전장품 15kg, 20~2000Hz 진동시험") @RequestParam String q) {
        return ApiResponse.ok(searchService.search(q));
    }
}
