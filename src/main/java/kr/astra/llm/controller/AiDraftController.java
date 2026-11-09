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
    @Operation(summary = "AI 의뢰 초안", description = "BE-B 초안 저장·결과 조회 연동 대기. 현재 성공 응답을 생성하지 않습니다.")
    @PostMapping("/test-requests/ai-draft") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AiDraftResponse> create(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody AiDraftRequest request) {
        return ApiResponse.ok(drafts.createForApi(user,request));
    }
}
