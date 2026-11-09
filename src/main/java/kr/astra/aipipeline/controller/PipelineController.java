package kr.astra.aipipeline.controller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.astra.aipipeline.dto.PipelineResponse;
import kr.astra.aipipeline.service.*;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;
@Tag(name = "AI 자동 추진") @RestController @RequestMapping("/ai/auto-runs") @RequiredArgsConstructor
public class PipelineController {
    private final PipelineService service;
    private final PipelineStore store;
    public record StartRequest(@Positive Long chatSessionId,@Size(max = 10000) String inputText) {}
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<PipelineResponse> start(@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody StartRequest input) {
        return ApiResponse.ok(service.start(user,input.chatSessionId(),input.inputText()));
    }
    @GetMapping("/{id}")
    public ApiResponse<PipelineResponse> get(@AuthenticationPrincipal CurrentUser user,@PathVariable Long id) {
        return ApiResponse.ok(store.get(user,id));
    }
    @PostMapping("/{id}/resume")
    public ApiResponse<PipelineResponse> resume(@AuthenticationPrincipal CurrentUser user,@PathVariable Long id) {
        return ApiResponse.ok(service.resume(user,id));
    }
    @PostMapping("/{id}/cancel")
    public ApiResponse<PipelineResponse> cancel(@AuthenticationPrincipal CurrentUser user,@PathVariable Long id) {
        return ApiResponse.ok(store.cancel(user,id));
    }
}
