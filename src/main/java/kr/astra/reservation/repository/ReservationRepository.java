package kr.astra.reservation.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

    /** 시연 일정 찾기 (같은 표시가 여러 개면 가장 최근 것) */
    Optional<Reservation> findFirstByExternalSystemAndExternalRefOrderByIdDesc(String externalSystem, String externalRef);

    /** 장비 달력: [from, to) 와 겹치는 확정 구간 */
    @Query("""
            SELECT r FROM Reservation r
            WHERE r.equipmentId = :equipmentId AND r.status = :status
              AND r.startAt < :to AND r.endAt > :from
            ORDER BY r.startAt
            """)
    List<Reservation> findBusy(@Param("equipmentId") Long equipmentId, @Param("status") ReservationStatus status,
                               @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /**
     * 대시보드: 기관의 다가오는 확정 예약 (아직 끝나지 않은 것, 의뢰도 시험 확정 상태. 완료된 의뢰는 빠짐. 시작 빠른 순).
     * 의뢰번호를 같이 가져와 행마다 의뢰를 다시 읽지 않음
     */
    @Query("""
            SELECT new kr.astra.reservation.repository.ReservationRepository$Upcoming(
                   r.id, q.id, q.requestNo, r.equipmentId, r.startAt, r.endAt)
            FROM Reservation r, TestRequest q
            WHERE r.requestId = q.id AND q.institutionId = :institutionId
              AND r.status = :status AND r.endAt > :now
              AND q.status = kr.astra.request.entity.RequestStatus.CONFIRMED
            ORDER BY r.startAt
            """)
    List<Upcoming> findUpcomingForInstitution(@Param("institutionId") Long institutionId,
                                              @Param("status") ReservationStatus status,
                                              @Param("now") OffsetDateTime now, Pageable pageable);

    /** 대시보드: 기업 회원 본인이 낸 의뢰의 다가오는 확정 예약 (아직 끝나지 않은 것, 시작 빠른 순) */
    @Query("""
            SELECT new kr.astra.reservation.repository.ReservationRepository$Upcoming(
                   r.id, q.id, q.requestNo, r.equipmentId, r.startAt, r.endAt)
            FROM Reservation r, TestRequest q
            WHERE r.requestId = q.id AND q.requesterId = :requesterId
              AND r.status = :status AND r.endAt > :now
              AND q.status = kr.astra.request.entity.RequestStatus.CONFIRMED
            ORDER BY r.startAt
            """)
    List<Upcoming> findUpcomingForRequester(@Param("requesterId") Long requesterId,
                                            @Param("status") ReservationStatus status,
                                            @Param("now") OffsetDateTime now, Pageable pageable);

    /** 의뢰를 잠그기 전에 의뢰 id 만 조회 (엔티티를 미리 읽어 두지 않으려고) */
    @Query("SELECT r.requestId FROM Reservation r WHERE r.id = :id")
    Optional<Long> findRequestIdById(@Param("id") Long id);

    record Upcoming(Long reservationId, Long requestId, String requestNo, Long equipmentId, OffsetDateTime startAt,
                    OffsetDateTime endAt) {
    }
}
