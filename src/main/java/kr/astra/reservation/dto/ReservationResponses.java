package kr.astra.reservation.dto;

import java.time.OffsetDateTime;
import kr.astra.request.dto.RequestResponses.ActionResult;
import kr.astra.request.dto.RequestResponses.PersonRef;
import kr.astra.request.entity.RequestAction;
import kr.astra.reservation.entity.ReservationStatus;

/** 예약 응답 (api-spec §7) */
public final class ReservationResponses {

    private ReservationResponses() {
    }

    /**
     * @param proposedBySide 제안한 쪽 (COMPANY / INSTITUTION). 상대편이 확정·대안 제안 버튼을 봅니다
     */
    public record ReservationView(
            Long id,
            Long requestId,
            Long equipmentId,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            ReservationStatus status,
            PersonRef proposedBy,
            RequestAction.Party proposedBySide,
            PersonRef confirmedBy,
            OffsetDateTime confirmedAt,
            String note,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    /** 예약 변경 결과: 예약 + 의뢰 상태 (확정하면 의뢰가 CONFIRMED 로 바뀜) */
    public record ReservationResult(ReservationView reservation, ActionResult request) {
    }

    /** GET /equipment/{id}/reservations — 확정된 바쁜 구간만 (어느 기업 의뢰인지는 보여 주지 않음) */
    public record BusySlot(OffsetDateTime startAt, OffsetDateTime endAt) {
    }
}
