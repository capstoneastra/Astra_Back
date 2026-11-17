package kr.astra.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import kr.astra.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * reservations: 장비 사용 일정. 시간 구간은 [start_at, end_at).
 * 같은 장비의 CONFIRMED 예약끼리 겹치면 DB 가 거부 (ex_resv_no_overlap, 23P01),
 * 의뢰 1건당 CONFIRMED 는 1개 (uq_resv_one_confirmed_per_request, 23505).
 */
@Getter
@Entity
@Table(name = "reservations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "start_at", nullable = false)
    private OffsetDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private OffsetDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "proposed_by", nullable = false)
    private Long proposedBy;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    /** 향후 기관 예약시스템 연동용 (지금은 비워 둠) */
    @Column(name = "external_system", length = 50)
    private String externalSystem;

    @Column(name = "external_ref", length = 100)
    private String externalRef;

    @Column(length = 500)
    private String note;

    /** 낙관적 잠금. 다른 트랜잭션이 먼저 바꿨으면 409 VERSION_CONFLICT */
    @Version
    @Column(nullable = false)
    private long version;

    public Reservation(Long requestId, Long equipmentId, OffsetDateTime startAt, OffsetDateTime endAt,
                       ReservationStatus status, Long proposedBy, String note) {
        this.requestId = requestId;
        this.equipmentId = equipmentId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.status = status;
        this.proposedBy = proposedBy;
        this.note = note;
    }

    /** 아직 확정 전인 제안 (PROPOSED / COUNTER_PROPOSED) */
    public boolean isOpen() {
        return status == ReservationStatus.PROPOSED || status == ReservationStatus.COUNTER_PROPOSED;
    }

    public void confirm(Long userId, OffsetDateTime now) {
        this.status = ReservationStatus.CONFIRMED;
        this.confirmedBy = userId;
        this.confirmedAt = now;
    }

    public void cancel() {
        this.status = ReservationStatus.CANCELED;
    }
}
