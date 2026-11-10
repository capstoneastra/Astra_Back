package kr.astra.reservation.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import kr.astra.reservation.entity.Reservation;
import kr.astra.reservation.entity.ReservationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /**
     * 같은 장비에서 [startAt, endAt) 와 겹치는 확정 예약 (중복 예약 1차 방어).
     * 끝과 시작이 맞닿는 경우(10:00~12:00 뒤 12:00~14:00)는 겹치지 않음 — DB 제약 '[)' 와 같은 기준.
     *
     * @param excludeId 자기 자신을 뺄 때 (없으면 -1)
     */
    @Query("""
            SELECT r FROM Reservation r
            WHERE r.equipmentId = :equipmentId AND r.status = :status
              AND r.startAt < :endAt AND r.endAt > :startAt AND r.id <> :excludeId
            ORDER BY r.startAt
            """)
    List<Reservation> findOverlaps(@Param("equipmentId") Long equipmentId,
                                   @Param("status") ReservationStatus status,
                                   @Param("startAt") OffsetDateTime startAt,
                                   @Param("endAt") OffsetDateTime endAt,
                                   @Param("excludeId") Long excludeId);

    List<Reservation> findAllByRequestIdOrderByCreatedAtDescIdDesc(Long requestId);

    List<Reservation> findAllByRequestIdAndStatusIn(Long requestId, Collection<ReservationStatus> statuses);

    boolean existsByRequestIdAndStatus(Long requestId, ReservationStatus status);

    /** 장비 달력: [from, to) 와 겹치는 확정 구간 */
    @Query("""
            SELECT r FROM Reservation r
            WHERE r.equipmentId = :equipmentId AND r.status = :status
              AND r.startAt < :to AND r.endAt > :from
            ORDER BY r.startAt
            """)
    List<Reservation> findBusy(@Param("equipmentId") Long equipmentId, @Param("status") ReservationStatus status,
                               @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** 대시보드: 기관의 다가오는 확정 예약 (시작 빠른 순) */
    @Query("""
            SELECT r FROM Reservation r, TestRequest q
            WHERE r.requestId = q.id AND q.institutionId = :institutionId
              AND r.status = :status AND r.startAt >= :now
            ORDER BY r.startAt
            """)
    List<Reservation> findUpcomingForInstitution(@Param("institutionId") Long institutionId,
                                                 @Param("status") ReservationStatus status,
                                                 @Param("now") OffsetDateTime now, Pageable pageable);
}
