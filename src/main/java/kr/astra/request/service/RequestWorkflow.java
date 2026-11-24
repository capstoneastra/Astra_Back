package kr.astra.request.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.security.AccessGuard;
import kr.astra.global.security.CurrentUser;
import kr.astra.notification.entity.NotificationType;
import kr.astra.notification.service.NotificationService;
import kr.astra.request.dto.RequestResponses.ActionResult;
import kr.astra.request.entity.RequestAction;
import kr.astra.request.entity.RequestStatus;
import kr.astra.request.entity.RequestStatusHistory;
import kr.astra.request.entity.TestRequest;
import kr.astra.request.entity.TestRequestRevision;
import kr.astra.request.repository.RequestStatusHistoryRepository;
import kr.astra.request.repository.TestRequestRepository;
import kr.astra.request.repository.TestRequestRevisionRepository;
import kr.astra.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 의뢰 상태머신. 모든 상태 변경은 {@link #begin} → (각 기능의 처리) → {@link #transition} 순서로 이 클래스를 거칩니다.
 * <ol>
 *   <li>begin: 행 잠금 → 권한(의뢰자 본인 / 장비 보유 조직 구성원) → 전이 가능 여부(§11) → version 확인</li>
 *   <li>transition: 상태 갱신 → request_status_history 저장 → 상대방 알림(§13) → version 증가 반영(flush)</li>
 * </ol>
 * 호출하는 서비스의 트랜잭션 하나에서 전부 처리되므로 중간에 실패하면 함께 롤백됩니다.
 * review·reservation 패키지도 이 클래스로 의뢰를 다룹니다 (TestRequest repository 를 직접 쓰지 않음).
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RequestWorkflow {

    private final TestRequestRepository requestRepository;
    private final TestRequestRevisionRepository revisionRepository;
    private final RequestStatusHistoryRepository historyRepository;
    private final NotificationService notificationService;
    private final UserService userService;
    private final ApplicationEventPublisher events;

    @PersistenceContext
    private EntityManager entityManager;

    // ------------------------------------------------------------ 조회·잠금

    @Transactional(propagation = Propagation.REQUIRED)
    public TestRequest get(Long requestId) {
        return requestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REQUEST_NOT_FOUND));
    }

    public TestRequest lock(Long requestId) {
        return requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REQUEST_NOT_FOUND));
    }

    /**
     * 상태를 바꾸기 전 공통 검사. 실패하면 401/403/404/409 를 던집니다.
     *
     * @param version 클라이언트가 보고 있던 version (null 이면 검사 생략)
     */
    public TestRequest begin(Long requestId, RequestAction action, CurrentUser actor, Integer version) {
        TestRequest request = lock(requestId);
        authorize(action.party(), actor, request);
        requireAllowed(request, action);
        checkVersion(request, version);
        return request;
    }

    /** 의뢰 당사자(의뢰자 본인 또는 장비 보유 조직 구성원)인지 확인하고 잠급니다. 예약처럼 양쪽이 다 하는 기능용 */
    public TestRequest lockForParty(Long requestId, CurrentUser actor, Integer version) {
        TestRequest request = lock(requestId);
        requireParty(actor, request);
        checkVersion(request, version);
        return request;
    }

    public void authorize(RequestAction.Party party, CurrentUser actor, TestRequest request) {
        if (party == RequestAction.Party.COMPANY) {
            AccessGuard.requireOwner(actor, request.getRequesterId());
        } else {
            AccessGuard.requireInstitution(actor, request.getInstitutionId());
            requireVisibleToInstitution(actor, request);
        }
    }

    /** 조회 권한: 의뢰자 본인, 장비 보유 조직 구성원(제출된 의뢰만), 관리자 */
    public void requireParty(CurrentUser actor, TestRequest request) {
        AccessGuard.requireOwnerOrInstitution(actor, request.getRequesterId(), request.getInstitutionId());
        requireVisibleToInstitution(actor, request);
    }

    /** 한 번도 제출하지 않은 작성 중 의뢰는 장비 보유 조직에 보이지 않습니다 */
    private static void requireVisibleToInstitution(CurrentUser actor, TestRequest request) {
        boolean ownerOrAdmin = actor.isAdmin() || request.getRequesterId().equals(actor.userId());
        if (!ownerOrAdmin && request.getSubmittedAt() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    public static void requireAllowed(TestRequest request, RequestAction action) {
        if (!action.allowedFrom(request.getStatus())) {
            throw invalidStatus(request, action);
        }
    }

    public static BusinessException invalidStatus(TestRequest request, RequestAction action) {
        return new BusinessException(ErrorCode.REQUEST_INVALID_STATUS,
                "현재 상태(" + request.getStatus() + ")에서는 할 수 없는 작업입니다.",
                Map.of("currentStatus", request.getStatus(), "action", action,
                        "allowedFrom", action.from()));
    }

    public static void checkVersion(TestRequest request, Integer version) {
        if (version != null && version != request.getVersion()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                    ErrorCode.VERSION_CONFLICT.defaultMessage(), Map.of("currentVersion", request.getVersion()));
        }
    }

    // ------------------------------------------------------------ 상태 전이

    /**
     * 상태를 바꾸고 이력·알림을 남깁니다. {@link #begin} 으로 검사한 의뢰에만 호출하세요.
     *
     * @param reason 반려·취소 사유 등 (없으면 null)
     */
    public void transition(TestRequest request, RequestAction action, CurrentUser actor, String reason) {
        transition(request, action, actor, reason, null);
    }

    /** notifyTitle 을 주면 기본 알림 문구 대신 그 문구로 상대방에게 알림 (예: 추가 정보 요청) */
    public void transition(TestRequest request, RequestAction action, CurrentUser actor, String reason,
                           String notifyTitle) {
        requireAllowed(request, action);
        RequestStatus from = request.getStatus();
        OffsetDateTime now = OffsetDateTime.now();
        request.changeStatus(action.to(), now);
        historyRepository.save(new RequestStatusHistory(request.getId(), from, action.to(), actor.userId(), reason));
        if (notifyTitle != null && action == RequestAction.REQUEST_SUPPLEMENT) {
            notifyRequester(request, NotificationType.SUPPLEMENT_REQUESTED, notifyTitle);
        } else {
            notifyFor(request, action, actor);
        }
        if (action.to() == RequestStatus.CANCELED || action.to() == RequestStatus.REJECTED) {
            events.publishEvent(new RequestClosedEvent(request.getId(), action.to()));
        }
        requestRepository.saveAndFlush(request); // @Version 증가를 응답에 바로 반영
    }

    /** 의뢰 생성 기록 (from=null → DRAFT) */
    public void recordCreated(TestRequest request, Long actorId, String reason) {
        historyRepository.save(new RequestStatusHistory(request.getId(), null, request.getStatus(), actorId, reason));
    }

    /** 상태는 그대로지만 내용이 바뀐 경우(작업본 저장) version 을 올려 동시 수정을 감지합니다 */
    public void bumpVersion(TestRequest request) {
        entityManager.lock(request, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        requestRepository.saveAndFlush(request);
    }

    // ------------------------------------------------------------ 제출본 (revision)

    /** 현재 번호의 제출본 (DRAFT 동안은 1번 작업본) */
    @Transactional(propagation = Propagation.REQUIRED)
    public TestRequestRevision currentRevision(TestRequest request) {
        return revisionRepository.findByRequestIdAndRevisionNo(request.getId(), request.getCurrentRevisionNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "의뢰의 현재 제출본이 없습니다: " + request.getRequestNo(), null));
    }

    /** 보완 요청 중 저장해 둔 다음 작업본 (current+1, 미제출) */
    @Transactional(propagation = Propagation.REQUIRED)
    public Optional<TestRequestRevision> nextWorkingRevision(TestRequest request) {
        return revisionRepository.findByRequestIdAndRevisionNo(request.getId(), request.getCurrentRevisionNo() + 1)
                .filter(r -> !r.isSubmitted());
    }

    /**
     * 지금 편집할 작업본. DRAFT 면 현재 번호 작업본, SUPPLEMENT_REQUESTED 면 current+1 (없으면 현재 제출본을 복사해 만듦).
     */
    public TestRequestRevision editableRevision(TestRequest request, Long authorId) {
        if (request.getStatus() == RequestStatus.DRAFT) {
            return currentRevision(request);
        }
        if (request.getStatus() == RequestStatus.SUPPLEMENT_REQUESTED) {
            return nextWorkingRevision(request).orElseGet(() -> revisionRepository.save(new TestRequestRevision(
                    request.getId(), request.getCurrentRevisionNo() + 1, currentRevision(request).getContent(),
                    false, null, authorId)));
        }
        throw new BusinessException(ErrorCode.REQUEST_INVALID_STATUS,
                "작성 중(DRAFT) 또는 보완 요청(SUPPLEMENT_REQUESTED) 상태에서만 의뢰서를 고칠 수 있습니다.",
                Map.of("currentStatus", request.getStatus()));
    }

    public TestRequestRevision saveRevision(TestRequestRevision revision) {
        return revisionRepository.save(revision);
    }

    // ------------------------------------------------------------ 알림 (api-spec §13)

    private void notifyFor(TestRequest request, RequestAction action, CurrentUser actor) {
        switch (action) {
            case SUBMIT -> notifyInstitution(request, NotificationType.REQUEST_SUBMITTED,
                    "새 시험 의뢰가 접수되었습니다: " + request.getTitle());
            case RESPOND_SUPPLEMENT -> notifyInstitution(request, NotificationType.REQUEST_RESUBMITTED,
                    "보완 자료가 제출되었습니다: " + request.getTitle());
            case REQUEST_SUPPLEMENT -> notifyRequester(request, NotificationType.SUPPLEMENT_REQUESTED,
                    "시험기관에서 보완을 요청했습니다.");
            case ACCEPT -> notifyRequester(request, NotificationType.REQUEST_ACCEPTED,
                    "기술 검토를 통과했습니다. 일정을 협의해 주세요.");
            case REJECT -> notifyRequester(request, NotificationType.REQUEST_REJECTED,
                    "의뢰가 반려되었습니다.");
            case CONFIRM_RESERVATION -> notifyRequester(request, NotificationType.RESERVATION_CONFIRMED,
                    "시험 일정이 확정되었습니다.");
            default -> {
                // START_REVIEW, COMPLETE, CANCEL: §13 에 알림 없음
            }
        }
    }

    public void notifyRequester(TestRequest request, NotificationType type, String title) {
        notificationService.notify(request.getRequesterId(), type, title, linkPath(request), request.getId());
    }

    /** 장비 보유 조직(기업·기관·대학)의 활성 구성원 전원 */
    public void notifyInstitution(TestRequest request, NotificationType type, String title) {
        List<Long> managers = userService.findActiveManagerIds(request.getInstitutionId());
        for (Long managerId : managers) {
            notificationService.notify(managerId, type, title, linkPath(request), request.getId());
        }
    }

    /** 행동한 사람의 상대편 (의뢰자가 했으면 장비 보유 조직 전원, 보유 조직·관리자가 했으면 의뢰자) */
    public void notifyCounterpart(TestRequest request, CurrentUser actor, NotificationType type, String title) {
        if (request.getRequesterId().equals(actor.userId())) {
            notifyInstitution(request, type, title);
        } else {
            notifyRequester(request, type, title);
        }
    }

    /** 상태 변경 API 공통 응답 */
    public static ActionResult result(TestRequest request) {
        return new ActionResult(request.getId(), request.getRequestNo(), request.getStatus(), request.getVersion(),
                request.getCurrentRevisionNo());
    }

    public static String linkPath(TestRequest request) {
        return "/requests/" + request.getId();
    }
}
