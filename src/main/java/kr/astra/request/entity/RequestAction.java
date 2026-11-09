package kr.astra.request.entity;

import static kr.astra.request.entity.RequestStatus.CANCELED;
import static kr.astra.request.entity.RequestStatus.COMPLETED;
import static kr.astra.request.entity.RequestStatus.CONFIRMED;
import static kr.astra.request.entity.RequestStatus.DRAFT;
import static kr.astra.request.entity.RequestStatus.REJECTED;
import static kr.astra.request.entity.RequestStatus.RESUBMITTED;
import static kr.astra.request.entity.RequestStatus.SCHEDULING;
import static kr.astra.request.entity.RequestStatus.SUBMITTED;
import static kr.astra.request.entity.RequestStatus.SUPPLEMENT_REQUESTED;
import static kr.astra.request.entity.RequestStatus.UNDER_REVIEW;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 의뢰 상태 전이표 (api-spec §11) 를 그대로 옮긴 것. 상태 변경은 모두 이 표를 거칩니다.
 * <pre>
 * DRAFT ─submit→ SUBMITTED ─review/start→ UNDER_REVIEW ─accept→ SCHEDULING ─reservation confirm→ CONFIRMED ─complete→ COMPLETED
 *                                         │  ▲
 *                              supplements│  │review/start
 *                                         ▼  │
 *                       SUPPLEMENT_REQUESTED ─respond→ RESUBMITTED
 * UNDER_REVIEW ─reject→ REJECTED      (DRAFT~SCHEDULING 에서 기업 cancel → CANCELED)
 * </pre>
 * 이 표를 바꾸면 FE 버튼 노출 기준도 바뀌므로 #api 방에 공지하세요.
 */
public enum RequestAction {

    SUBMIT(EnumSet.of(DRAFT), SUBMITTED, Party.COMPANY),
    START_REVIEW(EnumSet.of(SUBMITTED, RESUBMITTED), UNDER_REVIEW, Party.INSTITUTION),
    REQUEST_SUPPLEMENT(EnumSet.of(UNDER_REVIEW), SUPPLEMENT_REQUESTED, Party.INSTITUTION),
    RESPOND_SUPPLEMENT(EnumSet.of(SUPPLEMENT_REQUESTED), RESUBMITTED, Party.COMPANY),
    ACCEPT(EnumSet.of(UNDER_REVIEW), SCHEDULING, Party.INSTITUTION),
    REJECT(EnumSet.of(UNDER_REVIEW), REJECTED, Party.INSTITUTION),
    CONFIRM_RESERVATION(EnumSet.of(SCHEDULING), CONFIRMED, Party.INSTITUTION),
    COMPLETE(EnumSet.of(CONFIRMED), COMPLETED, Party.INSTITUTION),
    CANCEL(EnumSet.of(DRAFT, SUBMITTED, UNDER_REVIEW, SUPPLEMENT_REQUESTED, RESUBMITTED, SCHEDULING), CANCELED,
            Party.COMPANY);

    /** 이 액션을 하는 쪽 (관리자는 마스터라 양쪽 모두 가능) */
    public enum Party { COMPANY, INSTITUTION }

    private final Set<RequestStatus> from;
    private final RequestStatus to;
    private final Party party;

    RequestAction(Set<RequestStatus> from, RequestStatus to, Party party) {
        this.from = from;
        this.to = to;
        this.party = party;
    }

    public boolean allowedFrom(RequestStatus status) {
        return from.contains(status);
    }

    public Set<RequestStatus> from() {
        return from;
    }

    public RequestStatus to() {
        return to;
    }

    public Party party() {
        return party;
    }

    /** 현재 상태에서 그 쪽이 할 수 있는 액션 (상세 화면 버튼 노출용) */
    public static List<RequestAction> available(RequestStatus status, Party party) {
        return Arrays.stream(values()).filter(a -> a.party == party && a.allowedFrom(status)).toList();
    }
}
