package kr.astra.requirement.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.security.CurrentUser;
import kr.astra.requirement.dto.*;
import kr.astra.requirement.service.RequirementApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "시험조건")
@RestController
@RequestMapping("/requirements")
@RequiredArgsConstructor
public class RequirementController {
    private final RequirementApplicationService service;
    private final kr.astra.llm.service.RequirementAnalysisService analysis;
    public record ConditionsRequest(@NotNull @Size(max = 100) List<ConditionInput> conditions) {}
    public record UpdateRequest(@Size(max = 200) String title, @Size(max = 10000) String purpose,
            @Size(max = 200) String sampleName, @Positive Integer sampleQuantity) {}

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RequirementResponse> create(@AuthenticationPrincipal CurrentUser user,
            @Valid @RequestBody RequirementInput input) { return ApiResponse.ok(service.create(user, input)); }
    @GetMapping("/{id}")
    public ApiResponse<RequirementResponse> get(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        return ApiResponse.ok(service.get(user, id));
    }
    @PatchMapping("/{id}")
    public ApiResponse<RequirementResponse> update(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
            @Valid @RequestBody UpdateRequest input) {
        return ApiResponse.ok(service.update(user, id, input.title(), input.purpose(), input.sampleName(), input.sampleQuantity()));
    }
    @PutMapping("/{id}/conditions")
    public ApiResponse<RequirementResponse> conditions(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
            @Valid @RequestBody ConditionsRequest input) { return ApiResponse.ok(service.saveConditions(user, id, input.conditions())); }
    @PostMapping("/{id}/analyze")
    public ApiResponse<AnalysisResponse> analyze(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        return ApiResponse.ok(analysis.analyze(user, id));
    }
    @PostMapping("/{id}/confirm")
    public ApiResponse<RequirementResponse> confirm(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        return ApiResponse.ok(service.confirm(user, id));
    }
}
