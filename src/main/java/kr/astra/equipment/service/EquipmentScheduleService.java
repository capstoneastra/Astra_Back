package kr.astra.equipment.service;

import java.time.*;
import java.util.*;
import kr.astra.equipment.entity.*;
import kr.astra.equipment.repository.*;
import kr.astra.global.exception.*;
import kr.astra.global.security.*;
import kr.astra.reservation.service.ReservationAvailabilityQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class EquipmentScheduleService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final EquipmentRepository equipment;
    private final EquipmentDowntimeRepository downtimes;
    private final ReservationAvailabilityQuery reservations;
    public record Calendar(Long equipmentId, LocalDate from, LocalDate to, Availability availability,
                           boolean available, boolean needsConfirmation, Integer leadTimeDays,
                           List<AvailabilityCalculator.Interval> intervals) {}
    public Calendar availability(Long id, LocalDate from, LocalDate to) {
        Equipment e = find(id);
        Period period = period(from, to);
        OffsetDateTime a = period.startAt(), b = period.endAt();
        List<AvailabilityCalculator.Interval> blocks = new ArrayList<>();
        reservations.confirmed(id,a,b).forEach(r -> blocks.add(new AvailabilityCalculator.Interval(r.startAt(),r.endAt(),Availability.BUSY)));
        downtimes.overlapping(id,a,b).forEach(d -> blocks.add(new AvailabilityCalculator.Interval(d.startAt(),d.endAt(),Availability.MAINTENANCE)));
        boolean needs=e.availabilityNeedsConfirmation(OffsetDateTime.now());
        var intervals=AvailabilityCalculator.calculate(a,b,e.getAvailability(),needs,blocks);
        return new Calendar(id,period.from(),period.to(),e.getAvailability(),intervals.stream().allMatch(i -> i.availability()==Availability.AVAILABLE),
                needs,e.getLeadTimeDays(),intervals);
    }
    @Transactional
    public Long addDowntime(CurrentUser actor, Long id, OffsetDateTime start, OffsetDateTime end, String reason) {
        Equipment e=lock(id); AccessGuard.requireInstitution(actor,e.getInstitutionId());
        if (!end.isAfter(start)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        if (!reservations.confirmed(id,start,end).isEmpty()) throw new BusinessException(ErrorCode.RESERVATION_OVERLAP);
        return downtimes.insert(id,start,end,reason.strip(),actor.userId());
    }
    public List<EquipmentDowntimeRepository.Downtime> downtimes(CurrentUser actor, Long id, LocalDate from, LocalDate to) {
        Equipment e=find(id); AccessGuard.requireInstitution(actor,e.getInstitutionId());
        Period period = period(from, to);
        return downtimes.overlapping(id, period.startAt(), period.endAt());
    }
    @Transactional
    public void deleteDowntime(CurrentUser actor, Long id, Long downtimeId) {
        Equipment e=lock(id); AccessGuard.requireInstitution(actor,e.getInstitutionId());
        if (downtimes.delete(id,downtimeId)==0) throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    /** 예약 제안·확정과 정비 등록은 동일 장비 행을 잠근다. 호출 트랜잭션이 끝날 때까지 잠금 유지. */
    @Transactional
    public void requireReservable(Long id, OffsetDateTime from, OffsetDateTime to) {
        Equipment e=lock(id);
        if (e.getAvailability()==Availability.UNAVAILABLE || e.getAvailability()==Availability.MAINTENANCE
                || !downtimes.overlapping(id,from,to).isEmpty())
            throw new BusinessException(ErrorCode.RESERVATION_OVERLAP,"운영 상태 또는 정비·운휴 일정으로 예약할 수 없습니다.",null);
    }
    private Equipment find(Long id) { return equipment.findByIdAndActiveTrue(id).orElseThrow(() -> new BusinessException(ErrorCode.EQUIPMENT_NOT_FOUND)); }
    private Equipment lock(Long id) { return equipment.lockActive(id).orElseThrow(() -> new BusinessException(ErrorCode.EQUIPMENT_NOT_FOUND)); }

    private record Period(LocalDate from, LocalDate to, OffsetDateTime startAt, OffsetDateTime endAt) {}

    private static Period period(LocalDate from, LocalDate to) {
        LocalDate start = from == null ? LocalDate.now(KST) : from;
        LocalDate end = to == null ? start.plusDays(60) : to;
        if (end.isBefore(start) || java.time.temporal.ChronoUnit.DAYS.between(start, end) >= 366)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "조회 기간은 from ≤ to, 최대 366일입니다.", null);
        return new Period(start, end, start.atStartOfDay(KST).toOffsetDateTime(),
                end.plusDays(1).atStartOfDay(KST).toOffsetDateTime());
    }
}
