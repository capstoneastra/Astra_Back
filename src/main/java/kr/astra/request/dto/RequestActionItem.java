package kr.astra.request.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import kr.astra.request.entity.RequestStatus;

/**
 * 대시보드의 "처리할 의뢰" 한 줄 (RequestDashboardQuery 결과, api-spec §10 actionRequired·pendingReview)
 *
 * @param requestNo   REQ-20261105-0001
 * @param status      현재 상태 (예: SUPPLEMENT_REQUESTED, SUBMITTED)
 * @param dueDate     보완 요청 기한 등 (없으면 null)
 * @param submittedAt 제출 시각 (없으면 null)
 */
public record RequestActionItem(Long requestId, String requestNo, String title, RequestStatus status,
                                LocalDate dueDate, OffsetDateTime submittedAt) {
}
