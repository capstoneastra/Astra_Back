package kr.astra.llm.controller;
import jakarta.validation.Valid;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.security.CurrentUser;
import kr.astra.llm.dto.*;
import kr.astra.llm.service.AiDraftService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
@RestController @RequiredArgsConstructor
public class AiDraftController {
    private final AiDraftService drafts;
    @Operation(summary = "AI 의뢰 초안", description = "시험조건·선택 장비로 DRAFT 의뢰와 AI 1번 제출본을 만듭니다. 자동 제출하지 않습니다.")
    @PostMapping("/test-requests/ai-draft") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AiDraftResponse> create(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody AiDraftRequest request) {
        return ApiResponse.ok(drafts.createForApi(user,request));
    }
}
