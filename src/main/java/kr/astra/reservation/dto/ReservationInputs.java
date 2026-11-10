package kr.astra.reservation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** 예약 요청 본문 (api-spec §7). 시각은 ISO-8601 + 시간대 (2026-11-20T09:00:00+09:00) */
public final class ReservationInputs {

    private ReservationInputs() {
    }

    /**
     * POST /test-requests/{id}/reservations (일정 제안), POST /reservations/{id}/counter (대안 일정)
     *
     * @param version 의뢰 version (선택)
     */
    public record Propose(
            @NotNull OffsetDateTime startAt,
            @NotNull OffsetDateTime endAt,
            @Size(max = 500) String note,
            Integer version) {
    }
}
