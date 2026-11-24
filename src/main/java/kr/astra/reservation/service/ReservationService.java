package kr.astra.reservation.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.DbConstraints;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.security.CurrentUser;
import kr.astra.notification.entity.NotificationType;
import kr.astra.request.dto.UpcomingReservationItem;
import kr.astra.request.entity.RequestAction;
import kr.astra.request.entity.RequestStatus;
import kr.astra.request.entity.TestRequest;
import kr.astra.request.service.RequestClosedEvent;
import kr.astra.request.service.RequestViews;
import kr.astra.request.service.RequestWorkflow;
import kr.astra.reservation.dto.ReservationInputs;
import kr.astra.reservation.dto.ReservationResponses.BusySlot;
import kr.astra.reservation.dto.ReservationResponses.ReservationResult;
import kr.astra.reservation.dto.ReservationResponses.ReservationView;
import kr.astra.reservation.entity.Reservation;
import kr.astra.reservation.entity.ReservationStatus;
import kr.astra.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 제안·대안·확정·취소 (api-spec §7, 기능 11).
 * <p>
 * 중복 예약 방지 (필수): 1차로 서비스에서 같은 장비의 확정 예약과 겹치는지 조회하고,
 * 2차로 동시에 확정하는 경우는 DB 제약(ex_resv_no_overlap, uq_resv_one_confirmed_per_request)이 막습니다.
 * saveAndFlush() 로 그 자리에서 제약 위반을 받아 409 로 바꿉니다.
 * PROPOSED 끼리는 겹쳐도 됩니다 (확정된 일정만 막음).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ReservationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("M/d HH:mm");
    private static final Set<ReservationStatus> OPEN = EnumSet.of(ReservationStatus.PROPOSED,
            ReservationStatus.COUNTER_PROPOSED);
    /** 장비 달력 조회 기간 최대 (일) */
    private static final int MAX_RANGE_DAYS = 366;

    private final ReservationRepository reservationRepository;
    private final RequestWorkflow workflow;
    private final RequestViews views;
    private final EquipmentService equipmentService;
    private final kr.astra.equipment.service.EquipmentScheduleService equipmentSchedule;

    /** POST /test-requests/{id}/reservations — 일정 제안 (SCHEDULING 에서, 기업·기관 모두) */
    public ReservationResult propose(CurrentUser actor, Long requestId, ReservationInputs.Propose input) {
        TestRequest request = workflow.lockForParty(requestId, actor, input.version());
        requireScheduling(request);
        validatePeriod(input.startAt(), input.endAt());
        requireNoOverlap(request.getEquipmentId(), input.startAt(), input.endAt(), -1L);

        Reservation reservation = reservationRepository.save(new Reservation(request.getId(),
                request.getEquipmentId(), input.startAt(), input.endAt(), ReservationStatus.PROPOSED,
                actor.userId(), note(input.note())));
        workflow.notifyCounterpart(request, actor, NotificationType.RESERVATION_PROPOSED,
                "시험 일정이 제안되었습니다: " + period(reservation));
        return result(reservation, request);
    }

    /** POST /reservations/{id}/counter — 대안 일정. 원래 제안은 CANCELED, 새 제안은 COUNTER_PROPOSED */
    public ReservationResult counter(CurrentUser actor, Long reservationId, ReservationInputs.Propose input) {
        TestRequest request = workflow.lockForParty(requestIdOf(reservationId), actor, input.version());
        Reservation original = find(reservationId);
        requireScheduling(request);
        if (!original.isOpen()) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID_STATUS, "확정 전 제안에만 대안 일정을 낼 수 있습니다.",
                    Map.of("reservationStatus", original.getStatus()));
        }
        validatePeriod(input.startAt(), input.endAt());
        requireNoOverlap(request.getEquipmentId(), input.startAt(), input.endAt(), -1L);

        original.cancel();
        Reservation counter = reservationRepository.save(new Reservation(request.getId(), request.getEquipmentId(),
                input.startAt(), input.endAt(), ReservationStatus.COUNTER_PROPOSED, actor.userId(),
                note(input.note())));
        workflow.notifyCounterpart(request, actor, NotificationType.RESERVATION_PROPOSED,
                "대안 일정이 제안되었습니다: " + period(counter));
        return result(counter, request);
    }

    /**
     * POST /reservations/{id}/confirm — 기관이 확정 → 의뢰 SCHEDULING → CONFIRMED, 의뢰자에게 알림.
     * 같은 의뢰의 다른 제안은 CANCELED.
     */
    public ReservationResult confirm(CurrentUser actor, Long reservationId, Integer version) {
        TestRequest request = workflow.lock(requestIdOf(reservationId));
        workflow.authorize(RequestAction.CONFIRM_RESERVATION.party(), actor, request);
        Reservation reservation = find(reservationId);
        if (reservation.getStatus() == ReservationStatus.CONFIRMED
                || reservationRepository.existsByRequestIdAndStatus(request.getId(), ReservationStatus.CONFIRMED)) {
            throw new BusinessException(ErrorCode.RESERVATION_ALREADY_CONFIRMED);
        }
        if (!reservation.isOpen()) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID_STATUS, "취소된 일정은 확정할 수 없습니다.",
                    Map.of("reservationStatus", reservation.getStatus()));
        }
        RequestWorkflow.requireAllowed(request, RequestAction.CONFIRM_RESERVATION);
        RequestWorkflow.checkVersion(request, version);
        OffsetDateTime now = OffsetDateTime.now();
        if (!reservation.getStartAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "시작 시각이 지난 일정은 확정할 수 없습니다. 새 일정을 제안해 주세요.",
                    Map.of("field", "startAt"));
        }

        // 1차 방어: 서비스 조회
        requireNoOverlap(reservation.getEquipmentId(), reservation.getStartAt(), reservation.getEndAt(),
                reservation.getId());
        reservation.confirm(actor.userId(), now);
        // 2차 방어: 동시에 확정한 경우 DB 제약이 거부
        try {
            reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            String state = DbConstraints.sqlState(e);
            if (DbConstraints.EXCLUSION_VIOLATION.equals(state)
                    || DbConstraints.violates(e, DbConstraints.RESERVATION_NO_OVERLAP)) {
                throw new BusinessException(ErrorCode.RESERVATION_OVERLAP);
            }
            if (DbConstraints.UNIQUE_VIOLATION.equals(state)
                    || DbConstraints.violates(e, DbConstraints.RESERVATION_ONE_CONFIRMED)) {
                throw new BusinessException(ErrorCode.RESERVATION_ALREADY_CONFIRMED);
            }
            throw e;
        }
        for (Reservation other : reservationRepository.findAllByRequestIdAndStatusIn(request.getId(), OPEN)) {
            other.cancel();
        }
        workflow.transition(request, RequestAction.CONFIRM_RESERVATION, actor, period(reservation));
        return result(reservation, request);
    }

    /** POST /reservations/{id}/cancel — 확정 전 제안 취소 (기업·기관 모두). 확정된 일정은 취소할 수 없음 */
    public ReservationResult cancel(CurrentUser actor, Long reservationId, Integer version) {
        TestRequest request = workflow.lockForParty(requestIdOf(reservationId), actor, version);
        Reservation reservation = find(reservationId);
        if (!reservation.isOpen()) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID_STATUS,
                    reservation.getStatus() == ReservationStatus.CONFIRMED
                            ? "확정된 일정은 취소할 수 없습니다. 기관에 문의해 주세요." : "이미 취소된 일정입니다.",
                    Map.of("reservationStatus", reservation.getStatus()));
        }
        reservation.cancel();
        reservationRepository.flush();
        return result(reservation, request);
    }

    // ------------------------------------------------------------ 조회

    /** GET /test-requests/{id}/reservations — 의뢰의 일정 제안·확정 목록 (최신순) */
    @Transactional(readOnly = true)
    public List<ReservationView> listForRequest(CurrentUser actor, Long requestId) {
        TestRequest request = workflow.get(requestId);
        workflow.requireParty(actor, request);
        return viewsFor(request);
    }

    /** 의뢰 상세에 붙일 예약 목록 (권한 검사는 호출한 쪽에서) */
    @Transactional(readOnly = true)
    public List<ReservationView> viewsFor(TestRequest request) {
        List<Reservation> list = reservationRepository.findAllByRequestIdOrderByCreatedAtDescIdDesc(request.getId());
        RequestViews.Names names = views.people(userIds(list));
        return list.stream().map(r -> view(r, request, names)).toList();
    }

    /**
     * GET /equipment/{id}/reservations?from=&to= — 확정된 바쁜 구간 (날짜는 한국 날짜, to 포함).
     * 기본: 오늘부터 60일
     */
    @Transactional(readOnly = true)
    public List<BusySlot> busy(Long equipmentId, LocalDate from, LocalDate to) {
        equipmentService.getDetail(equipmentId); // 없거나 비활성이면 404 EQUIPMENT_NOT_FOUND
        LocalDate start = from == null ? LocalDate.now(KST) : from;
        LocalDate end = to == null ? start.plusDays(60) : to;
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "조회 기간은 from ≤ to, 최대 " + MAX_RANGE_DAYS + "일입니다.", Map.of("field", "to"));
        }
        return reservationRepository.findBusy(equipmentId, ReservationStatus.CONFIRMED,
                        start.atStartOfDay(KST).toOffsetDateTime(), end.plusDays(1).atStartOfDay(KST).toOffsetDateTime())
                .stream().map(r -> new BusySlot(r.getStartAt(), r.getEndAt())).toList();
    }

    /** 대시보드: 기관의 다가오는 확정 예약 */
    @Transactional(readOnly = true)
    public List<UpcomingReservationItem> upcomingForInstitution(Long institutionId, int limit) {
        List<ReservationRepository.Upcoming> rows = reservationRepository.findUpcomingForInstitution(institutionId,
                ReservationStatus.CONFIRMED, OffsetDateTime.now(), PageRequest.of(0, limit));
        RequestViews.Names names = views.equipmentNames(rows.stream().map(ReservationRepository.Upcoming::equipmentId)
                .toList());
        return rows.stream().map(r -> new UpcomingReservationItem(r.reservationId(), r.requestId(), r.requestNo(),
                r.equipmentId(), names.equipment(r.equipmentId()).name(), r.startAt(), r.endAt())).toList();
    }

    /** 의뢰가 반려·취소되면 확정 전 제안을 모두 취소합니다 (같은 트랜잭션) */
    @EventListener
    public void onRequestClosed(RequestClosedEvent event) {
        reservationRepository.findAllByRequestIdAndStatusIn(event.requestId(), OPEN).forEach(Reservation::cancel);
    }

    // ------------------------------------------------------------ 내부

    /**
     * 예약의 의뢰 id. 확정·대안·취소는 이 id 로 의뢰를 먼저 잠근 뒤 {@link #find} 로 예약을 읽습니다
     * (잠금 전에 읽은 사본은 잠금을 기다리는 동안 다른 트랜잭션이 바꿨을 수 있음).
     */
    private Long requestIdOf(Long reservationId) {
        return reservationRepository.findRequestIdById(reservationId).orElseThrow(ReservationService::notFound);
    }

    private Reservation find(Long id) {
        return reservationRepository.findById(id).orElseThrow(ReservationService::notFound);
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "예약을 찾을 수 없습니다.", null);
    }

    private static void requireScheduling(TestRequest request) {
        if (request.getStatus() != RequestStatus.SCHEDULING) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID_STATUS, "일정 협의(SCHEDULING) 상태에서만 일정을 제안할 수 있습니다.",
                    Map.of("currentStatus", request.getStatus()));
        }
    }

    private static void validatePeriod(OffsetDateTime startAt, OffsetDateTime endAt) {
        if (!endAt.isAfter(startAt)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "종료 시각은 시작 시각보다 뒤여야 합니다.",
                    Map.of("field", "endAt"));
        }
        if (startAt.isBefore(OffsetDateTime.now())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "지난 시각으로는 일정을 제안할 수 없습니다.",
                    Map.of("field", "startAt"));
        }
    }

    /** 1차 방어: 같은 장비의 확정 예약과 겹치면 409 RESERVATION_OVERLAP + 겹치는 구간 */
    private void requireNoOverlap(Long equipmentId, OffsetDateTime startAt, OffsetDateTime endAt, Long excludeId) {
        equipmentSchedule.requireReservable(equipmentId, startAt, endAt);
        List<Reservation> conflicts = reservationRepository.findOverlaps(equipmentId, ReservationStatus.CONFIRMED,
                startAt, endAt, excludeId);
        if (!conflicts.isEmpty()) {
            throw new BusinessException(ErrorCode.RESERVATION_OVERLAP, Map.of("conflicts", conflicts.stream()
                    .map(c -> new BusySlot(c.getStartAt(), c.getEndAt())).toList()));
        }
    }

    private static String note(String note) {
        return note == null || note.isBlank() ? null : note.strip();
    }

    private static String period(Reservation r) {
        return r.getStartAt().atZoneSameInstant(KST).format(WHEN) + " ~ "
                + r.getEndAt().atZoneSameInstant(KST).format(WHEN);
    }

    private ReservationResult result(Reservation reservation, TestRequest request) {
        return new ReservationResult(view(reservation, request, views.people(userIds(List.of(reservation)))),
                RequestWorkflow.result(request));
    }

    /** 제안한 사람·확정한 사람 id */
    private static Set<Long> userIds(List<Reservation> list) {
        Set<Long> ids = new HashSet<>();
        for (Reservation r : list) {
            ids.add(r.getProposedBy());
            if (r.getConfirmedBy() != null) {
                ids.add(r.getConfirmedBy());
            }
        }
        return ids;
    }

    private static ReservationView view(Reservation r, TestRequest request, RequestViews.Names names) {
        RequestAction.Party side = r.getProposedBy().equals(request.getRequesterId())
                ? RequestAction.Party.COMPANY : RequestAction.Party.INSTITUTION;
        return new ReservationView(r.getId(), r.getRequestId(), r.getEquipmentId(), r.getStartAt(), r.getEndAt(),
                r.getStatus(), names.person(r.getProposedBy()), side, names.person(r.getConfirmedBy()),
                r.getConfirmedAt(), r.getNote(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
