package kr.astra.review.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.security.CurrentUser;
import kr.astra.request.dto.RequestInputs;
import kr.astra.request.dto.RequestResponses.ActionResult;
import kr.astra.review.dto.ReviewInputs;
import kr.astra.review.dto.ReviewResponses.SupplementCreated;
import kr.astra.review.dto.ReviewResponses.SupplementView;
import kr.astra.review.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "기관 검토·보완", description = "검토 시작·보완 요청·보완 제출·검토 통과·반려·시험 완료 (api-spec §6, 상태 전이 §11). "
        + "기관 담당자는 소속 기관으로 들어온 의뢰만, 보완 제출은 의뢰한 기업 본인만. version 을 보내면 불일치 시 409")
@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "검토 시작", description = "SUBMITTED/RESUBMITTED → UNDER_REVIEW. assigneeId 를 비우면 요청한 담당자 본인")
    @PostMapping("/test-requests/{id}/review/start")
    public ApiResponse<ActionResult> startReview(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
                                                 @RequestBody(required = false) ReviewInputs.StartReview request) {
        return ApiResponse.ok(reviewService.startReview(user, id, request));
    }

    @Operation(summary = "보완 요청", description = "UNDER_REVIEW → SUPPLEMENT_REQUESTED, 의뢰자에게 알림. "
            + "답변 대기 중인 보완 요청이 있으면 409 SUPPLEMENT_ALREADY_OPEN")
    @PostMapping("/test-requests/{id}/supplements")
    public ResponseEntity<ApiResponse<SupplementCreated>> requestSupplement(
            @AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
            @Valid @RequestBody ReviewInputs.CreateSupplement request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(reviewService.requestSupplement(user, id, request)));
    }

    @Operation(summary = "보완 요청 목록", description = "최신순")
    @GetMapping("/test-requests/{id}/supplements")
    public ApiResponse<List<SupplementView>> supplements(@AuthenticationPrincipal CurrentUser user,
                                                         @PathVariable Long id) {
        return ApiResponse.ok(reviewService.list(user, id));
    }

    @Operation(summary = "보완 제출", description = "새 제출본(revision) 생성 → RESUBMITTED, 기관 담당자에게 알림. "
            + "content 를 비우면 PUT /draft 로 저장한 작업본을 제출. 필수 보완 항목이 비면 400 (details.missingItems)")
    @PostMapping("/supplements/{id}/respond")
    public ApiResponse<ActionResult> respond(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
                                             @Valid @RequestBody ReviewInputs.Respond request) {
        return ApiResponse.ok(reviewService.respond(user, id, request));
    }

    @Operation(summary = "기술 검토 통과", description = "UNDER_REVIEW → SCHEDULING, 의뢰자에게 알림")
    @PostMapping("/test-requests/{id}/accept")
    public ApiResponse<ActionResult> accept(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
                                            @RequestBody(required = false) RequestInputs.VersionOnly request) {
        return ApiResponse.ok(reviewService.accept(user, id, request == null ? null : request.version()));
    }

    @Operation(summary = "반려", description = "UNDER_REVIEW → REJECTED (사유 필수), 의뢰자에게 알림. 열린 보완 요청·제안 중 예약은 닫힘")
    @PostMapping("/test-requests/{id}/reject")
    public ApiResponse<ActionResult> reject(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
                                            @Valid @RequestBody RequestInputs.Reject request) {
        return ApiResponse.ok(reviewService.reject(user, id, request.version(), request.reason()));
    }

    @Operation(summary = "시험 완료 처리", description = "CONFIRMED → COMPLETED")
    @PostMapping("/test-requests/{id}/complete")
    public ApiResponse<ActionResult> complete(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
                                              @RequestBody(required = false) RequestInputs.VersionOnly request) {
        return ApiResponse.ok(reviewService.complete(user, id, request == null ? null : request.version()));
    }
}
