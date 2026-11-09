package kr.astra.review.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.DbConstraints;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.security.CurrentUser;
import kr.astra.request.dto.RequestResponses.ActionResult;
import kr.astra.request.entity.RequestAction;
import kr.astra.request.entity.TestRequest;
import kr.astra.request.entity.TestRequestRevision;
import kr.astra.request.service.RequestClosedEvent;
import kr.astra.request.service.RequestContent;
import kr.astra.request.service.RequestViews;
import kr.astra.request.service.RequestWorkflow;
import kr.astra.review.dto.ReviewInputs;
import kr.astra.review.dto.ReviewResponses.SupplementCreated;
import kr.astra.review.dto.ReviewResponses.SupplementView;
import kr.astra.review.entity.SupplementRequest;
import kr.astra.review.entity.SupplementStatus;
import kr.astra.review.repository.SupplementRequestRepository;
import kr.astra.user.entity.UserRole;
import kr.astra.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기관 기술 검토·보완 (api-spec §6, 기능 10). 상태 변경은 모두 RequestWorkflow 를 거칩니다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ReviewService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final TypeReference<List<ReviewInputs.Item>> ITEMS = new TypeReference<>() {
    };

    private final SupplementRequestRepository supplementRepository;
    private final RequestWorkflow workflow;
    private final RequestContent content;
    private final RequestViews views;
    private final UserService userService;
    private final JsonMapper jsonMapper;

    /** POST /test-requests/{id}/review/start — SUBMITTED/RESUBMITTED → UNDER_REVIEW, 담당자 지정 */
    public ActionResult startReview(CurrentUser actor, Long requestId, ReviewInputs.StartReview input) {
        TestRequest request = workflow.begin(requestId, RequestAction.START_REVIEW, actor,
                input == null ? null : input.version());
        Long assigneeId = input == null ? null : input.assigneeId();
        if (assigneeId == null && actor.role() == UserRole.INSTITUTION_MANAGER) {
            assigneeId = actor.userId();
        }
        if (assigneeId != null && !userService.findActiveManagerIds(request.getInstitutionId()).contains(assigneeId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "담당자는 접수 기관의 활성 기관 담당자여야 합니다.",
                    Map.of("field", "assigneeId"));
        }
        request.assign(assigneeId);
        workflow.transition(request, RequestAction.START_REVIEW, actor, null);
        return RequestWorkflow.result(request);
    }

    /**
     * POST /test-requests/{id}/supplements — UNDER_REVIEW → SUPPLEMENT_REQUESTED.
     * 답변 대기 중(OPEN)인 요청이 있으면 409 SUPPLEMENT_ALREADY_OPEN (서비스 검사 + DB 유니크 인덱스 이중 방어)
     */
    public SupplementCreated requestSupplement(CurrentUser actor, Long requestId,
                                               ReviewInputs.CreateSupplement input) {
        TestRequest request = workflow.lock(requestId);
        workflow.authorize(RequestAction.REQUEST_SUPPLEMENT.party(), actor, request);
        if (supplementRepository.existsByRequestIdAndStatus(requestId, SupplementStatus.OPEN)) {
            throw new BusinessException(ErrorCode.SUPPLEMENT_ALREADY_OPEN);
        }
        RequestWorkflow.requireAllowed(request, RequestAction.REQUEST_SUPPLEMENT);
        RequestWorkflow.checkVersion(request, input.version());
        if (input.dueDate() != null && input.dueDate().isBefore(LocalDate.now(KST))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "보완 기한은 오늘 이후여야 합니다.",
                    Map.of("field", "dueDate"));
        }
        List<ReviewInputs.Item> items = input.items() == null ? List.of() : input.items();
        SupplementRequest supplement = new SupplementRequest(requestId, request.getCurrentRevisionNo(),
                actor.userId(), input.message().strip(), jsonMapper.writeValueAsString(items), input.dueDate());
        try {
            supplement = supplementRepository.saveAndFlush(supplement);
        } catch (DataIntegrityViolationException e) {
            if (DbConstraints.violates(e, DbConstraints.SUPPLEMENT_ONE_OPEN)) {
                throw new BusinessException(ErrorCode.SUPPLEMENT_ALREADY_OPEN);
            }
            throw e;
        }
        workflow.transition(request, RequestAction.REQUEST_SUPPLEMENT, actor, input.message().strip());
        return new SupplementCreated(view(supplement, views.people(List.of(actor.userId()))),
                RequestWorkflow.result(request));
    }

    /**
     * POST /supplements/{id}/respond — 보완 제출. 새 제출본(current+1)을 만들고 current_revision_no 를 올립니다.
     * required=true 인 보완 항목이 비어 있으면 400 VALIDATION_FAILED (details.missingItems).
     */
    public ActionResult respond(CurrentUser actor, Long supplementId, ReviewInputs.Respond input) {
        SupplementRequest supplement = supplementRepository.findById(supplementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "보완 요청을 찾을 수 없습니다.", null));
        TestRequest request = workflow.begin(supplement.getRequestId(), RequestAction.RESPOND_SUPPLEMENT, actor,
                input.version());
        if (supplement.getStatus() != SupplementStatus.OPEN) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID_STATUS, "이미 답변했거나 닫힌 보완 요청입니다.",
                    Map.of("supplementStatus", supplement.getStatus()));
        }

        TestRequestRevision working = workflow.editableRevision(request, actor.userId());
        Map<String, Object> body = input.content() != null ? input.content() : content.read(working.getContent());
        content.validate(body);
        List<String> missing = readItems(supplement).stream()
                .filter(ReviewInputs.Item::required)
                .filter(item -> !content.isFilled(body, item.fieldKey()))
                .map(item -> item.label() == null ? item.fieldKey() : item.label())
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "보완 요청 항목을 모두 채워 주세요.",
                    Map.of("missingItems", missing));
        }

        OffsetDateTime now = OffsetDateTime.now();
        working.updateContent(content.write(body), input.changeSummary(), actor.userId());
        working.submit(now);
        workflow.saveRevision(working);
        request.advanceRevision(working.getRevisionNo());
        RequestContent.Schedule s = content.schedule(body);
        request.updateDraftInfo(null, s.start(), s.end());
        supplement.respond(working.getRevisionNo(), input.message(), now);
        workflow.transition(request, RequestAction.RESPOND_SUPPLEMENT, actor, input.changeSummary());
        return RequestWorkflow.result(request);
    }

    /** POST /test-requests/{id}/accept — 기술 검토 통과 UNDER_REVIEW → SCHEDULING */
    public ActionResult accept(CurrentUser actor, Long requestId, Integer version) {
        TestRequest request = workflow.begin(requestId, RequestAction.ACCEPT, actor, version);
        request.assign(request.getAssigneeId() == null && actor.role() == UserRole.INSTITUTION_MANAGER
                ? actor.userId() : null);
        workflow.transition(request, RequestAction.ACCEPT, actor, null);
        return RequestWorkflow.result(request);
    }

    /** POST /test-requests/{id}/reject — UNDER_REVIEW → REJECTED (사유 필수) */
    public ActionResult reject(CurrentUser actor, Long requestId, Integer version, String reason) {
        TestRequest request = workflow.begin(requestId, RequestAction.REJECT, actor, version);
        workflow.transition(request, RequestAction.REJECT, actor, reason.strip());
        return RequestWorkflow.result(request);
    }

    /** POST /test-requests/{id}/complete — 시험 완료 CONFIRMED → COMPLETED */
    public ActionResult complete(CurrentUser actor, Long requestId, Integer version) {
        TestRequest request = workflow.begin(requestId, RequestAction.COMPLETE, actor, version);
        workflow.transition(request, RequestAction.COMPLETE, actor, null);
        return RequestWorkflow.result(request);
    }

    // ------------------------------------------------------------ 조회

    /** GET /test-requests/{id}/supplements (최신순) */
    @Transactional(readOnly = true)
    public List<SupplementView> list(CurrentUser actor, Long requestId) {
        TestRequest request = workflow.get(requestId);
        workflow.requireParty(actor, request);
        List<SupplementRequest> list = supplementRepository.findAllByRequestIdOrderByCreatedAtDescIdDesc(requestId);
        RequestViews.Names names = views.people(list.stream().map(SupplementRequest::getRequestedBy).toList());
        return list.stream().map(s -> view(s, names)).toList();
    }

    /** 의뢰 상세에 붙일 열린 보완 요청 (권한 검사는 호출한 쪽에서) */
    @Transactional(readOnly = true)
    public Optional<SupplementView> findOpen(Long requestId) {
        return supplementRepository.findFirstByRequestIdAndStatus(requestId, SupplementStatus.OPEN)
                .map(s -> view(s, views.people(List.of(s.getRequestedBy()))));
    }

    /** 대시보드: 의뢰 id → 열린 보완 요청의 기한 */
    @Transactional(readOnly = true)
    public Map<Long, LocalDate> openDueDates(Collection<Long> requestIds) {
        Map<Long, LocalDate> due = new HashMap<>();
        if (requestIds.isEmpty()) {
            return due;
        }
        for (SupplementRequest s : supplementRepository.findAllByRequestIdInAndStatus(requestIds,
                SupplementStatus.OPEN)) {
            if (s.getDueDate() != null) {
                due.put(s.getRequestId(), s.getDueDate());
            }
        }
        return due;
    }

    /** 의뢰가 반려·취소되면 답변 대기 중인 보완 요청을 닫습니다 (같은 트랜잭션) */
    @EventListener
    public void onRequestClosed(RequestClosedEvent event) {
        supplementRepository.findAllByRequestIdAndStatus(event.requestId(), SupplementStatus.OPEN)
                .forEach(SupplementRequest::close);
    }

    private List<ReviewInputs.Item> readItems(SupplementRequest s) {
        return s.getItems() == null || s.getItems().isBlank() ? List.of() : jsonMapper.readValue(s.getItems(), ITEMS);
    }

    private SupplementView view(SupplementRequest s, RequestViews.Names names) {
        return new SupplementView(s.getId(), s.getRequestId(), s.getStatus(), s.getBasedRevisionNo(),
                names.person(s.getRequestedBy()), s.getMessage(), readItems(s), s.getDueDate(),
                s.getResponseRevisionNo(), s.getResponseMessage(), s.getCreatedAt(), s.getRespondedAt());
    }
}
