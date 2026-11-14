package kr.astra.reservation.service;

import java.time.OffsetDateTime;
import java.util.List;
import kr.astra.reservation.dto.ReservationResponses.BusySlot;
import kr.astra.reservation.entity.ReservationStatus;
import kr.astra.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** BE-B → BE-A. 예약자·의뢰 내용은 노출하지 않는 확정 시간 조회. */
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class ReservationAvailabilityQuery {
    private final ReservationRepository reservations;
    public List<BusySlot> confirmed(Long id, OffsetDateTime from, OffsetDateTime to) {
        return reservations.findBusy(id, ReservationStatus.CONFIRMED, from, to).stream()
                .map(r -> new BusySlot(r.getStartAt(), r.getEndAt())).toList();
    }
}
