package kr.astra.equipment.service;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import kr.astra.equipment.entity.*;
import kr.astra.equipment.service.AvailabilityCalculator.Interval;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AvailabilityCalculatorTest {
    OffsetDateTime at(int hour) { return OffsetDateTime.parse("2026-11-20T%02d:00:00+09:00".formatted(hour)); }
    @Test void combinesOverlappingMaintenanceAndReservationsWithoutInventingFreeTime() {
        var result=AvailabilityCalculator.calculate(at(9),at(18),Availability.AVAILABLE,false,List.of(
                new Interval(at(8),at(11),Availability.BUSY),new Interval(at(10),at(12),Availability.MAINTENANCE),
                new Interval(at(12),at(14),Availability.BUSY),new Interval(at(18),at(20),Availability.BUSY)));
        assertThat(result).containsExactly(new Interval(at(9),at(10),Availability.BUSY),
                new Interval(at(10),at(12),Availability.MAINTENANCE),new Interval(at(12),at(14),Availability.BUSY),
                new Interval(at(14),at(18),Availability.AVAILABLE));
    }
    @Test void unknownStaleAndManualOutagesNeverBecomeAvailableWhenCalendarIsEmpty() {
        assertThat(AvailabilityCalculator.calculate(at(9),at(18),Availability.AVAILABLE,true,List.of()).getFirst().availability()).isEqualTo(Availability.UNKNOWN);
        for (var state:List.of(Availability.UNKNOWN,Availability.UNAVAILABLE,Availability.MAINTENANCE,Availability.BUSY))
            assertThat(AvailabilityCalculator.calculate(at(9),at(18),state,false,List.of()).getFirst().availability()).isEqualTo(state);
    }
    @Test void equalInstantsWithDifferentOffsetsDoNotCreateExtraIntervals() {
        var result=AvailabilityCalculator.calculate(at(9),at(18),Availability.AVAILABLE,false,List.of(
                new Interval(at(9).withOffsetSameInstant(ZoneOffset.UTC),at(18).withOffsetSameInstant(ZoneOffset.UTC),Availability.BUSY)));
        assertThat(result).containsExactly(new Interval(at(9),at(18),Availability.BUSY));
    }
    @Test void freshnessUsesAvailabilityConfirmationRatherThanGeneralEditTime() {
        Equipment e=org.springframework.beans.BeanUtils.instantiateClass(Equipment.class);
        ReflectionTestUtils.setField(e,"availability",Availability.AVAILABLE);
        ReflectionTestUtils.setField(e,"availabilityUpdatedBy",1L);
        ReflectionTestUtils.setField(e,"availabilityUpdatedAt",at(9).minusDays(90));
        assertThat(e.availabilityNeedsConfirmation(at(9))).isFalse();
        assertThat(e.availabilityNeedsConfirmation(at(9).plusNanos(1))).isTrue();
        ReflectionTestUtils.setField(e,"infoUpdatedAt",at(18));
        assertThat(e.availabilityNeedsConfirmation(at(18))).isTrue();
        ReflectionTestUtils.setField(e,"availabilityUpdatedAt",at(18));
        ReflectionTestUtils.setField(e,"availabilityUpdatedBy",null);
        assertThat(e.availabilityNeedsConfirmation(at(18))).isTrue(); // DEMO/import는 기관 확인 아님
    }
}
