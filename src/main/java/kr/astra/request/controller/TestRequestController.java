package kr.astra.request.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.response.PageResponse;
import kr.astra.global.security.CurrentUser;
import kr.astra.request.dto.RequestBox;
import kr.astra.request.dto.RequestInputs;
import kr.astra.request.dto.RequestResponses.ActionResult;
import kr.astra.request.dto.RequestResponses.Detail;
import kr.astra.request.dto.RequestResponses.DraftSaved;
import kr.astra.request.dto.RequestResponses.History;
import kr.astra.request.dto.RequestResponses.Revision;
import kr.astra.request.dto.RequestResponses.RevisionDetail;
import kr.astra.request.dto.RequestResponses.Summary;
import kr.astra.request.entity.RequestStatus;
import kr.astra.request.service.RequestCommandService;
import kr.astra.request.service.RequestQueryService;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "시험 의뢰", description = "의뢰 작성·작업본 저장·제출·취소, 목록·상세·제출본 이력·상태 이력 (api-spec §5, 상태 전이 §11). "
        + "의뢰자는 본인 의뢰, 장비 보유 조직 구성원은 소속 조직으로 제출된 의뢰, 관리자는 전체. "
        + "상태를 바꾸는 요청에 version 을 보내면 불일치 시 409 VERSION_CONFLICT. AI 초안(POST /test-requests/ai-draft)은 BE-C")
@RestController
@RequestMapping("/test-requests")
@RequiredArgsConstructor
public class TestRequestController {

    private final RequestCommandService commandService;
    private final RequestQueryService queryService;

    @Operation(summary = "의뢰 생성", description = "DRAFT + 1번 작업본. requirementId 가 있으면 시험조건을 의뢰서 conditions 로 옮김. "
            + "접수 기관은 장비 소속 기관. 응답은 의뢰 상세")
    @PostMapping
    public ResponseEntity<ApiResponse<Detail>> create(@AuthenticationPrincipal CurrentUser user,
                                                      @Valid @RequestBody RequestInputs.Create request) {
        Long id = commandService.create(user, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(queryService.detail(user, id)));
    }

    @Operation(summary = "의뢰 목록", description = "?box=SENT 내가 보낸 의뢰 / ?box=RECEIVED 우리 조직 장비로 들어온 의뢰(제출된 것만) / "
            + "관리자: 전체. box 를 안 보내면 INSTITUTION_MANAGER 는 RECEIVED, 그 외는 SENT. "
            + "?status=UNDER_REVIEW, 정렬 createdAt·updatedAt·submittedAt·requestNo·status·desiredStartDate·title")
    @GetMapping
    public ApiResponse<PageResponse<Summary>> list(@AuthenticationPrincipal CurrentUser user,
                                                   @RequestParam(required = false) RequestBox box,
                                                   @RequestParam(required = false) RequestStatus status,
                                                   @ParameterObject @PageableDefault(size = 20, sort = "createdAt",
                                                           direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(queryService.list(user, box, status, pageable));
    }

    @Operation(summary = "의뢰 상세", description = "현재 상태·현재 제출본·열린 보완 요청·예약·누를 수 있는 버튼(availableActions)")
    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        return ApiResponse.ok(queryService.detail(user, id));
    }

    @Operation(summary = "작업본 저장", description = "DRAFT 이면 1번 작업본, SUPPLEMENT_REQUESTED 이면 다음 번호 작업본에 저장 (제출은 안 함)")
    @PutMapping("/{id}/draft")
    public ApiResponse<DraftSaved> saveDraft(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
                                             @Valid @RequestBody RequestInputs.SaveDraft request) {
        return ApiResponse.ok(commandService.saveDraft(user, id, request));
    }

    @Operation(summary = "제출", description = "DRAFT → SUBMITTED, 1번 제출본 고정, 장비 보유 조직 구성원 전원에게 알림")
    @PostMapping("/{id}/submit")
    public ApiResponse<ActionResult> submit(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
                                            @RequestBody(required = false) RequestInputs.VersionOnly request) {
        return ApiResponse.ok(commandService.submit(user, id, request == null ? null : request.version()));
    }

    @Operation(summary = "예상 이용료 입력", description = "장비 보유 조직이 검토 중~일정 조율 중에 금액(원)과 메모를 적음. 응답은 의뢰 상세")
    @PutMapping("/{id}/estimated-fee")
    public ApiResponse<Detail> estimatedFee(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
                                            @Valid @RequestBody RequestInputs.EstimatedFee request) {
        commandService.updateEstimatedFee(user, id, request);
        return ApiResponse.ok(queryService.detail(user, id));
    }

    @Operation(summary = "시험 결과·성적서 등록", description = "장비 보유 조직이 일정 확정(CONFIRMED) 뒤 결과 요약·성적서 번호·링크를 등록(다시 보내면 수정). 결과가 있어야 완료 가능. 응답은 의뢰 상세")
    @PutMapping("/{id}/result")
    public ApiResponse<Detail> result(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
                                      @Valid @RequestBody RequestInputs.Result request) {
        commandService.registerResult(user, id, request);
        return ApiResponse.ok(queryService.detail(user, id));
    }

    @Operation(summary = "취소", description = "DRAFT~SCHEDULING → CANCELED. 열린 보완 요청과 확정 전 일정 제안도 함께 닫힘")
    @PostMapping("/{id}/cancel")
    public ApiResponse<ActionResult> cancel(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
                                            @Valid @RequestBody(required = false) RequestInputs.Cancel request) {
        return ApiResponse.ok(commandService.cancel(user, id, request));
    }

    @Operation(summary = "제출본 이력", description = "revision 번호 순 (본문 제외). 미제출 작업본은 의뢰자에게만")
    @GetMapping("/{id}/revisions")
    public ApiResponse<List<Revision>> revisions(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        return ApiResponse.ok(queryService.revisions(user, id));
    }

    @Operation(summary = "특정 제출본", description = "?diffWith=1 이면 그 제출본과 바뀐 값(changes) 포함. "
            + "path 예: sample.readyDate, conditions.fixture_mass.value")
    @GetMapping("/{id}/revisions/{no}")
    public ApiResponse<RevisionDetail> revision(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
                                                @PathVariable("no") int revisionNo,
                                                @RequestParam(required = false) Integer diffWith) {
        return ApiResponse.ok(queryService.revision(user, id, revisionNo, diffWith));
    }

    @Operation(summary = "상태 변경 이력", description = "오래된 순. 의뢰 생성 기록은 fromStatus=null")
    @GetMapping("/{id}/history")
    public ApiResponse<List<History>> history(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        return ApiResponse.ok(queryService.history(user, id));
    }
}
