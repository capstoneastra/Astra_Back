package kr.astra.request.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.astra.request.dto.RequestActionItem;
import kr.astra.request.dto.UpcomingReservationItem;
import kr.astra.request.entity.RequestStatus;
import kr.astra.request.entity.TestRequest;
import kr.astra.request.repository.TestRequestRepository;
import kr.astra.reservation.service.ReservationService;
import kr.astra.review.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [BE-B 구현 → BE-A 사용] 대시보드 의뢰·예약 집계 (api-spec §10).
 * 이 빈이 있으면 BE-A DashboardService 가 dataAvailable.requests=true 로 채웁니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestDashboardQueryImpl implements RequestDashboardQuery {

    /** 기업이 손대야 하는 상태: 보완 요청 답변, 일정 협의, 작성 중 */
    private static final Set<RequestStatus> COMPANY_TODO = EnumSet.of(RequestStatus.SUPPLEMENT_REQUESTED,
            RequestStatus.SCHEDULING, RequestStatus.DRAFT);
    private static final Set<RequestStatus> PENDING_REVIEW = EnumSet.of(RequestStatus.SUBMITTED,
            RequestStatus.RESUBMITTED);
    /** 목록을 고를 때 넉넉히 읽어 와서 정렬 후 자름 (보완 기한 순 정렬은 다른 테이블 값이라 DB 정렬이 안 됨) */
    private static final int SCAN = 50;

    private final TestRequestRepository requestRepository;
    private final ReviewService reviewService;
    private final ReservationService reservationService;

    @Override
    public Map<RequestStatus, Long> countByRequester(Long requesterId) {
        return toMap(requestRepository.countStatusByRequester(requesterId));
    }

    @Override
    public Map<RequestStatus, Long> countByInstitution(Long institutionId) {
        return toMap(requestRepository.countStatusByInstitution(institutionId));
    }

    @Override
    public Map<RequestStatus, Long> countAll() {
        return toMap(requestRepository.countStatusAll());
    }

    @Override
    public Map<String, Long> countByTestType() {
        Map<String, Long> counts = new java.util.HashMap<>();
        for (Object[] row : requestRepository.countNonDraftByTestType()) {
            if (row[0] != null) {
                counts.put((String) row[0], ((Number) row[1]).longValue());
            }
        }
        return counts;
    }

    /** 보완 요청(기한 빠른 순) → 일정 협의 → 작성 중 순서, 같은 묶음은 최근 수정 순 */
    @Override
    public List<RequestActionItem> findActionRequired(Long requesterId, int limit) {
        List<TestRequest> candidates = requestRepository.findAllByRequesterIdAndStatusIn(requesterId, COMPANY_TODO,
                PageRequest.of(0, Math.max(limit, SCAN), Sort.by(Sort.Direction.DESC, "updatedAt")));
        Map<Long, LocalDate> due = reviewService.openDueDates(candidates.stream().map(TestRequest::getId).toList());
        Comparator<TestRequest> order = Comparator
                .comparingInt((TestRequest r) -> rank(r.getStatus()))
                .thenComparing((TestRequest r) -> due.get(r.getId()),
                        Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()));
        return candidates.stream().sorted(order).limit(limit)
                .map(r -> item(r, due.get(r.getId()))).toList();
    }

    @Override
    public List<RequestActionItem> findPendingReview(Long institutionId, int limit) {
        return requestRepository.findAllByInstitutionIdAndStatusIn(institutionId, PENDING_REVIEW,
                        PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "updatedAt")))
                .stream().map(r -> item(r, null)).toList();
    }

    @Override
    public List<UpcomingReservationItem> findUpcomingReservations(Long institutionId, int limit) {
        return reservationService.upcomingForInstitution(institutionId, limit);
    }

    @Override
    public List<UpcomingReservationItem> findUpcomingReservationsForRequester(Long requesterId, int limit) {
        return reservationService.upcomingForRequester(requesterId, limit);
    }

    private static int rank(RequestStatus status) {
        return switch (status) {
            case SUPPLEMENT_REQUESTED -> 0;
            case SCHEDULING -> 1;
            default -> 2;
        };
    }

    private static RequestActionItem item(TestRequest r, LocalDate dueDate) {
        return new RequestActionItem(r.getId(), r.getRequestNo(), r.getTitle(), r.getStatus(), dueDate,
                r.getSubmittedAt());
    }

    private static Map<RequestStatus, Long> toMap(List<Object[]> rows) {
        Map<RequestStatus, Long> counts = new EnumMap<>(RequestStatus.class);
        for (Object[] row : rows) {
            counts.put((RequestStatus) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }
}
