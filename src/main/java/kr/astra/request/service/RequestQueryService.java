package kr.astra.request.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.response.PageResponse;
import kr.astra.global.security.AccessGuard;
import kr.astra.global.security.CurrentUser;
import kr.astra.request.dto.RequestBox;
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

/**
 * 의뢰 조회 (api-spec §5). 보낸 의뢰(SENT)는 본인이 만든 것, 받은 의뢰(RECEIVED)는 소속 조직 장비로 제출된 것, 관리자는 전체.
 * 기업·기관·대학 회원 모두 두 목록을 가질 수 있습니다.
 */
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
    private final kr.astra.request.repository.TestResultRepository resultRepository;

    /**
     * GET /test-requests?box=&status=
     *
     * @param box 보낸 의뢰(SENT) / 받은 의뢰(RECEIVED). 안 보내면 가입 유형에 맞는 기본 목록
     *            (INSTITUTION_MANAGER 는 받은 의뢰, 그 외는 보낸 의뢰). 관리자는 box 와 무관하게 전체
     */
    public PageResponse<Summary> list(CurrentUser actor, RequestBox box, RequestStatus status, Pageable pageable) {
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
        } else if (resolveBox(actor, box) == RequestBox.RECEIVED) {
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

    private static RequestBox resolveBox(CurrentUser actor, RequestBox box) {
        if (box != null) {
            return box;
        }
        return actor.role() == UserRole.INSTITUTION_MANAGER ? RequestBox.RECEIVED : RequestBox.SENT;
    }

    /** GET /test-requests/{id} — 현재 상태, 현재 제출본, 열린 보완 요청, 예약, 누를 수 있는 버튼 */
    public Detail detail(CurrentUser actor, Long requestId) {
        TestRequest r = workflow.get(requestId);
        workflow.requireParty(actor, r);

        TestRequestRevision current = workflow.currentRevision(r);
        boolean ownerOrAdmin = ownerOrAdmin(actor, r);
        TestRequestRevision working = ownerOrAdmin ? workflow.nextWorkingRevision(r).orElse(null) : null;

        Set<Long> userIds = new java.util.HashSet<>(RequestViews.authorIds(Stream.of(current, working)));
        var result = resultRepository.findByRequestId(r.getId()).orElse(null);
        if (result != null) {
            userIds.add(result.getRegisteredBy());
        }
        RequestViews.Names names = views.names(r, userIds);
        return new Detail(r.getId(), r.getRequestNo(), r.getTitle(), r.getStatus(), r.getVersion(),
                r.getCurrentRevisionNo(), names.equipment(r.getEquipmentId()), names.org(r.getInstitutionId()),
                names.person(r.getRequesterId()), names.orgName(r.getCompanyOrgId()), names.person(r.getAssigneeId()),
                r.getRequirementId(), r.getMatchResultId(), r.getDesiredStartDate(), r.getDesiredEndDate(),
                r.getSubmittedAt(), r.getClosedAt(), r.getCreatedAt(), r.getUpdatedAt(),
                views.revision(current, names, true), views.revision(working, names, true),
                reviewService.findOpen(r.getId()).orElse(null), reservationService.viewsFor(r),
                availableActions(actor, r),
                r.getEstimatedFee() == null ? null
                        : new kr.astra.request.dto.RequestResponses.EstimatedFee(r.getEstimatedFee(), r.getEstimatedFeeNote(),
                                r.getEstimatedFeeUpdatedAt()),
                result == null ? null
                        : new kr.astra.request.dto.RequestResponses.ResultView(result.getSummary(), result.getReportNo(),
                                result.getReportUrl(), names.person(result.getRegisteredBy()), result.getRegisteredAt(),
                                result.getUpdatedAt()));
    }

    /** GET /test-requests/{id}/revisions — 제출본 이력 (본문 제외). 아직 제출 안 한 작업본은 의뢰자·관리자에게만 */
    public List<Revision> revisions(CurrentUser actor, Long requestId) {
        TestRequest r = workflow.get(requestId);
        workflow.requireParty(actor, r);
        boolean ownerOrAdmin = ownerOrAdmin(actor, r);
        List<TestRequestRevisionRepository.Meta> list = revisionRepository.findMetaByRequestId(requestId).stream()
                .filter(rev -> ownerOrAdmin || rev.submitted())
                .toList();
        RequestViews.Names names = views.people(list.stream().map(TestRequestRevisionRepository.Meta::authorId)
                .collect(Collectors.toSet()));
        return list.stream().map(rev -> new Revision(rev.revisionNo(), rev.aiGenerated(), rev.submitted(),
                rev.changeSummary(), names.person(rev.authorId()), rev.createdAt(), rev.submittedAt(), null)).toList();
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
        boolean ownerOrAdmin = ownerOrAdmin(actor, r);
        return revisionRepository.findByRequestIdAndRevisionNo(r.getId(), revisionNo)
                .filter(rev -> ownerOrAdmin || rev.isSubmitted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "제출본을 찾을 수 없습니다: " + revisionNo,
                        null));
    }

    /** 의뢰자 본인 또는 관리자 (아직 제출 안 한 작업본을 볼 수 있음) */
    private static boolean ownerOrAdmin(CurrentUser actor, TestRequest r) {
        return actor.isAdmin() || r.getRequesterId().equals(actor.userId());
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
