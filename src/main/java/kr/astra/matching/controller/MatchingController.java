package kr.astra.matching.controller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.astra.global.response.*;
import kr.astra.global.security.CurrentUser;
import kr.astra.matching.dto.MatchResponse;
import kr.astra.matching.service.MatchingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "규칙 기반 매칭") @RestController @RequiredArgsConstructor
public class MatchingController {
    private final MatchingService service;
    public record MatchRequest(@Size(max = 50) String region, @Min(1) @Max(100) Integer limit) {}
    @PostMapping("/requirements/{id}/match-runs")
    public ApiResponse<MatchResponse> run(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
            @Valid @RequestBody(required = false) MatchRequest input) {
        return ApiResponse.ok(service.run(user, id, input == null ? null : input.region(),
                input == null || input.limit() == null ? 5 : input.limit()));
    }
    @GetMapping("/match-runs/{id}")
    public ApiResponse<MatchResponse> get(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        return ApiResponse.ok(service.get(user, id));
    }
    @GetMapping("/requirements/{id}/match-runs")
    public ApiResponse<PageResponse<MatchResponse>> history(@AuthenticationPrincipal CurrentUser user,
            @PathVariable Long id, Pageable page) { return ApiResponse.ok(service.history(user, id, page)); }
}
