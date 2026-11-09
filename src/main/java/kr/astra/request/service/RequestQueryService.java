package kr.astra.request.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.response.PageResponse;
import kr.astra.global.security.AccessGuard;
import kr.astra.global.security.CurrentUser;
import kr.astra.request.dto.RequestResponses.Detail;
import kr.astra.request.dto.RequestResponses.History;
import kr.astra.request.dto.RequestResponses.Revision;
import kr.astra.request.dto.RequestResponses.RevisionDetail;
import kr.astra.request.dto.RequestResponses.Summary;
import kr.astra.request.entity.RequestAction;
import kr.astra.request.entity.RequestStatus;
import kr.astra.request.entity.RequestStatusHistory;
import kr.astra.request.entity.TestRequest;
import kr.astra.request.entity.TestRequestRevision;
import kr.astra.request.repository.RequestStatusHistoryRepository;
import kr.astra.request.repository.TestRequestRepository;
import kr.astra.request.repository.TestRequestRevisionRepository;
import kr.astra.reservation.service.ReservationService;
import kr.astra.review.service.ReviewService;
import kr.astra.user.entity.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 의뢰 조회 (api-spec §5). 기업은 본인 의뢰, 기관 담당자는 소속 기관 접수함(제출된 것만), 관리자는 전체 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestQueryService {

    /** 목록 정렬에 쓸 수 있는 필드 (그 외는 400) */
    private static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "submittedAt", "requestNo",
            "status", "desiredStartDate", "title", "id");

    private final TestRequestRepository requestRepository;
    private final TestRequestRevisionRepository revisionRepository;
    private final RequestStatusHistoryRepository historyRepository;
    private final RequestWorkflow workflow;
    private final RequestViews views;
    private final RequestContent content;
    private final ReviewService reviewService;
    private final ReservationService reservationService;

    /** GET /test-requests?status= */
    public PageResponse<Summary> list(CurrentUser actor, RequestStatus status, Pageable pageable) {
        AccessGuard.requireLogin(actor);
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "정렬할 수 없는 필드입니다: " + order.getProperty(),
                        Map.of("field", "sort", "allowed", SORTABLE));
            }
        }
        Page<TestRequest> page;
        if (actor.isAdmin()) {
            page = status == null ? requestRepository.findAll(pageable)
                    : requestRepository.findAllByStatus(status, pageable);
        } else if (actor.role() == UserRole.INSTITUTION_MANAGER) {
            page = status == null
                    ? requestRepository.findAllByInstitutionIdAndSubmittedAtIsNotNull(actor.organizationId(), pageable)
                    : requestRepository.findAllByInstitutionIdAndStatusAndSubmittedAtIsNotNull(actor.organizationId(),
                    status, pageable);
        } else {
            page = status == null ? requestRepository.findAllByRequesterId(actor.userId(), pageable)
                    : requestRepository.findAllByRequesterIdAndStatus(actor.userId(), status, pageable);
        }
        RequestViews.Names names = views.names(page.getContent(), List.of());
        return PageResponse.from(page, r -> views.summary(r, names));
    }

    /** GET /test-requests/{id} — 현재 상태, 현재 제출본, 열린 보완 요청, 예약, 누를 수 있는 버튼 */
    public Detail detail(CurrentUser actor, Long requestId) {
        TestRequest r = workflow.get(requestId);
        workflow.requireParty(actor, r);

        TestRequestRevision current = workflow.currentRevision(r);
        boolean ownerOrAdmin = actor.isAdmin() || r.getRequesterId().equals(actor.userId());
        TestRequestRevision working = ownerOrAdmin ? workflow.nextWorkingRevision(r).orElse(null) : null;

        Set<Long> userIds = RequestViews.authorIds(Stream.of(current, working));
        RequestViews.Names names = views.names(r, userIds);
        return new Detail(r.getId(), r.getRequestNo(), r.getTitle(), r.getStatus(), r.getVersion(),
                r.getCurrentRevisionNo(), names.equipment(r.getEquipmentId()), names.org(r.getInstitutionId()),
                names.person(r.getRequesterId()), names.orgName(r.getCompanyOrgId()), names.person(r.getAssigneeId()),
                r.getRequirementId(), r.getMatchResultId(), r.getDesiredStartDate(), r.getDesiredEndDate(),
                r.getSubmittedAt(), r.getClosedAt(), r.getCreatedAt(), r.getUpdatedAt(),
                views.revision(current, names, true), views.revision(working, names, true),
                reviewService.findOpen(r.getId()).orElse(null), reservationService.viewsFor(r),
                availableActions(actor, r));
    }

    /** GET /test-requests/{id}/revisions — 제출본 이력 (본문 제외). 아직 제출 안 한 작업본은 의뢰자·관리자에게만 */
    public List<Revision> revisions(CurrentUser actor, Long requestId) {
        TestRequest r = workflow.get(requestId);
        workflow.requireParty(actor, r);
        boolean ownerOrAdmin = actor.isAdmin() || r.getRequesterId().equals(actor.userId());
        List<TestRequestRevision> list = revisionRepository.findAllByRequestIdOrderByRevisionNoAsc(requestId).stream()
                .filter(rev -> ownerOrAdmin || rev.isSubmitted())
                .toList();
        RequestViews.Names names = views.people(RequestViews.authorIds(list.stream()));
        return list.stream().map(rev -> views.revision(rev, names, false)).toList();
    }

    /** GET /test-requests/{id}/revisions/{no}?diffWith=1 — 특정 제출본 (+ 다른 제출본과 바뀐 값) */
    public RevisionDetail revision(CurrentUser actor, Long requestId, int revisionNo, Integer diffWith) {
        TestRequest r = workflow.get(requestId);
        workflow.requireParty(actor, r);
        TestRequestRevision rev = visibleRevision(actor, r, revisionNo);
        TestRequestRevision base = diffWith == null ? null : visibleRevision(actor, r, diffWith);
        RequestViews.Names names = views.people(RequestViews.authorIds(Stream.of(rev, base)));
        Revision view = views.revision(rev, names, true);
        return new RevisionDetail(view, diffWith,
                base == null ? null : content.diff(content.read(base.getContent()), view.content()));
    }

    /** GET /test-requests/{id}/history — 상태 변경 이력 (오래된 순) */
    public List<History> history(CurrentUser actor, Long requestId) {
        TestRequest r = workflow.get(requestId);
        workflow.requireParty(actor, r);
        List<RequestStatusHistory> list = historyRepository.findAllByRequestIdOrderByCreatedAtAscIdAsc(requestId);
        Set<Long> userIds = new HashSet<>();
        list.forEach(h -> userIds.add(h.getChangedBy()));
        RequestViews.Names names = views.people(userIds);
        return list.stream().map(h -> new History(h.getFromStatus(), h.getToStatus(), names.person(h.getChangedBy()),
                h.getReason(), h.getCreatedAt())).toList();
    }

    private TestRequestRevision visibleRevision(CurrentUser actor, TestRequest r, int revisionNo) {
        boolean ownerOrAdmin = actor.isAdmin() || r.getRequesterId().equals(actor.userId());
        return revisionRepository.findByRequestIdAndRevisionNo(r.getId(), revisionNo)
                .filter(rev -> ownerOrAdmin || rev.isSubmitted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "제출본을 찾을 수 없습니다: " + revisionNo,
                        null));
    }

    /** 상세 화면 버튼 (§11 표 기준). 관리자는 양쪽 버튼을 모두 봄 */
    private static List<RequestAction> availableActions(CurrentUser actor, TestRequest r) {
        if (actor.isAdmin()) {
            return Stream.concat(RequestAction.available(r.getStatus(), RequestAction.Party.COMPANY).stream(),
                    RequestAction.available(r.getStatus(), RequestAction.Party.INSTITUTION).stream()).toList();
        }
        RequestAction.Party party = r.getRequesterId().equals(actor.userId())
                ? RequestAction.Party.COMPANY : RequestAction.Party.INSTITUTION;
        return RequestAction.available(r.getStatus(), party);
    }
}
