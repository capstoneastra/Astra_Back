package kr.astra.request.dto;

import java.time.OffsetDateTime;

/** 대시보드의 "다가오는 예약" 한 줄 (RequestDashboardQuery 결과, api-spec §10 upcomingReservations) */
public record UpcomingReservationItem(Long reservationId, Long requestId, String requestNo, Long equipmentId,
                                      String equipmentName, OffsetDateTime startAt, OffsetDateTime endAt) {
}
