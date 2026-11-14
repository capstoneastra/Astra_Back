package kr.astra.equipment.service;

import java.time.*;
import java.util.*;
import kr.astra.equipment.entity.Availability;

/** [시작, 종료) 경계. 정비와 예약을 합치며 빈 구간도 원래 상태의 확인 여부를 유지한다. */
public final class AvailabilityCalculator {
    private AvailabilityCalculator() {}
    public record Interval(OffsetDateTime startAt, OffsetDateTime endAt, Availability availability) {}
    public static List<Interval> calculate(OffsetDateTime from, OffsetDateTime to, Availability base,
                                           boolean needsConfirmation, List<Interval> blocked) {
        if (!to.isAfter(from)) throw new IllegalArgumentException("Invalid interval");
        Availability fallback = base == Availability.AVAILABLE && needsConfirmation ? Availability.UNKNOWN : base;
        SortedSet<Instant> cuts = new TreeSet<>(List.of(from.toInstant(),to.toInstant()));
        for (Interval i : blocked) {
            if (!i.endAt().isAfter(from) || !i.startAt().isBefore(to)) continue;
            cuts.add(i.startAt().isAfter(from) ? i.startAt().toInstant() : from.toInstant());
            cuts.add(i.endAt().isBefore(to) ? i.endAt().toInstant() : to.toInstant());
        }
        List<Instant> points = new ArrayList<>(cuts);
        List<Interval> result = new ArrayList<>();
        for (int n=0;n<points.size()-1;n++) {
            Instant start=points.get(n), end=points.get(n+1);
            Availability state=fallback;
            for (Interval b : blocked) if (b.startAt().toInstant().isBefore(end) && b.endAt().toInstant().isAfter(start)) {
                if (priority(b.availability()) > priority(state)) state=b.availability();
            }
            if (!result.isEmpty() && result.getLast().availability()==state) {
                Interval last=result.removeLast();
                result.add(new Interval(last.startAt(),end.atOffset(from.getOffset()),state));
            } else result.add(new Interval(start.atOffset(from.getOffset()),end.atOffset(from.getOffset()),state));
        }
        return List.copyOf(result);
    }
    private static int priority(Availability state) {
        return switch(state) { case UNAVAILABLE -> 5; case MAINTENANCE -> 4; case BUSY -> 3; case UNKNOWN -> 2; case AVAILABLE -> 1; };
    }
}
