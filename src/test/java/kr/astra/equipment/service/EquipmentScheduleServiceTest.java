package kr.astra.equipment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import kr.astra.equipment.entity.*;
import kr.astra.equipment.repository.*;
import kr.astra.equipment.repository.EquipmentDowntimeRepository.Downtime;
import kr.astra.global.exception.*;
import kr.astra.global.security.CurrentUser;
import kr.astra.reservation.dto.ReservationResponses.BusySlot;
import kr.astra.reservation.service.ReservationAvailabilityQuery;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.Test;

class EquipmentScheduleServiceTest {
    private final EquipmentRepository equipment = mock(EquipmentRepository.class);
    private final EquipmentDowntimeRepository downtimes = mock(EquipmentDowntimeRepository.class);
    private final ReservationAvailabilityQuery reservations = mock(ReservationAvailabilityQuery.class);
    private final EquipmentScheduleService service = new EquipmentScheduleService(equipment,downtimes,reservations);
    private final Equipment e = mock(Equipment.class);
    private final LocalDate date = LocalDate.of(2027,1,1);
    private final OffsetDateTime start = date.atTime(10,0).atOffset(ZoneOffset.ofHours(9));
    private final OffsetDateTime end = start.plusHours(2);
    private final CurrentUser owner = new CurrentUser(3L,UserRole.INSTITUTION_MANAGER,2L);

    @Test
    void 다른_기관은_정비_등록과_삭제를_할_수_없다() {
        locked();
        var outsider = new CurrentUser(4L,UserRole.INSTITUTION_MANAGER,5L);
        assertError(() -> service.addDowntime(outsider,48L,start,end,"정비"),ErrorCode.FORBIDDEN);
        assertError(() -> service.deleteDowntime(outsider,48L,1L),ErrorCode.FORBIDDEN);
        verifyNoInteractions(downtimes,reservations);
    }

    @Test
    void 확정_예약과_정비_등록은_같은_장비를_잠그고_겹침을_거부한다() {
        locked();
        when(reservations.confirmed(48L,start,end)).thenReturn(List.of(new BusySlot(start,end)));
        assertError(() -> service.addDowntime(owner,48L,start,end,"정비"),ErrorCode.RESERVATION_OVERLAP);
        verify(equipment).lockActive(48L);
        verifyNoInteractions(downtimes);
    }

    @Test
    void 예약은_정비_구간과_이용중단_상태를_거부한다() {
        locked();
        when(e.getAvailability()).thenReturn(Availability.AVAILABLE);
        when(downtimes.overlapping(48L,start,end)).thenReturn(List.of(new Downtime(1L,48L,start,end,"정비",3L,start)));
        assertError(() -> service.requireReservable(48L,start,end),ErrorCode.RESERVATION_OVERLAP);
        when(downtimes.overlapping(48L,start,end)).thenReturn(List.of());
        assertThatCode(() -> service.requireReservable(48L,start,end)).doesNotThrowAnyException();
        when(e.getAvailability()).thenReturn(Availability.UNAVAILABLE);
        assertError(() -> service.requireReservable(48L,start,end),ErrorCode.RESERVATION_OVERLAP);
    }

    @Test
    void 조회_마지막날을_포함하고_총_366일까지만_허용한다() {
        when(equipment.findByIdAndActiveTrue(48L)).thenReturn(Optional.of(e));
        when(e.getAvailability()).thenReturn(Availability.UNKNOWN);
        when(e.availabilityNeedsConfirmation(any())).thenReturn(true);
        var calendar = service.availability(48L,date,date);
        assertThat(calendar.available()).isFalse();
        assertThat(calendar.needsConfirmation()).isTrue();
        assertThat(calendar.intervals().getFirst().endAt()).isEqualTo(date.plusDays(1).atStartOfDay().atOffset(ZoneOffset.ofHours(9)));
        assertThatCode(() -> service.availability(48L,date,date.plusDays(365))).doesNotThrowAnyException();
        assertError(() -> service.availability(48L,date,date.plusDays(366)),ErrorCode.VALIDATION_FAILED);
        assertError(() -> service.availability(48L,date,date.minusDays(1)),ErrorCode.VALIDATION_FAILED);
    }

    private void locked() {
        when(equipment.lockActive(48L)).thenReturn(Optional.of(e));
        when(e.getInstitutionId()).thenReturn(2L);
    }
    private void assertError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }
}
