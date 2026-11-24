package kr.astra.request.service;

import java.util.List;
import java.util.Map;
import kr.astra.request.dto.RequestActionItem;
import kr.astra.request.dto.UpcomingReservationItem;
import kr.astra.request.entity.RequestStatus;

/**
 * [BE-B 구현 → BE-A 사용] 대시보드(GET /dashboard/summary, api-spec §10)용 의뢰·예약 집계.
 * <p>
 * BE-A 는 {@code ObjectProvider<RequestDashboardQuery>} 로 받아서, 구현이 없으면 0·빈 목록으로 응답합니다.
 * 구현 클래스에 {@code @Service} 를 붙이면 대시보드에 자동으로 채워집니다. 시그니처를 바꾸려면 BE-A 와 먼저 맞춰 주세요.
 * 없는 상태는 Map 에 넣지 않아도 됩니다 (BE-A 가 0 으로 채움).
 */
public interface RequestDashboardQuery {

    /** 기업 회원 본인이 낸 의뢰의 상태별 개수 (test_requests.requester_id) */
    Map<RequestStatus, Long> countByRequester(Long requesterId);

    /** 기관으로 들어온 의뢰의 상태별 개수 (test_requests.institution_id) */
    Map<RequestStatus, Long> countByInstitution(Long institutionId);

    /** 전체 의뢰의 상태별 개수 (관리자) */
    Map<RequestStatus, Long> countAll();

    /** 작성 중(DRAFT)을 뺀 전체 의뢰의 시험유형 코드별 개수 (관리자, 시험유형을 알 수 없는 의뢰는 빠짐) */
    default Map<String, Long> countByTestType() {
        return Map.of();
    }

    /**
     * 기업 회원이 지금 처리해야 할 의뢰 (예: SUPPLEMENT_REQUESTED 보완 요청, 기한 빠른 순)
     *
     * @param limit 최대 개수
     */
    List<RequestActionItem> findActionRequired(Long requesterId, int limit);

    /** 기관이 검토를 시작해야 할 의뢰 (SUBMITTED·RESUBMITTED, 제출 오래된 순) */
    List<RequestActionItem> findPendingReview(Long institutionId, int limit);

    /** 기관의 다가오는 확정 예약 (start_at 이 지금 이후, 빠른 순) */
    List<UpcomingReservationItem> findUpcomingReservations(Long institutionId, int limit);
}
