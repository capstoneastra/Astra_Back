package kr.astra.reservation.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.astra.equipment.dto.EquipmentDetailResponse;
import kr.astra.equipment.entity.Equipment;
import kr.astra.equipment.repository.EquipmentRepository;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.global.security.CurrentUser;
import kr.astra.request.dto.RequestInputs;
import kr.astra.request.dto.RequestResponses.Detail;
import kr.astra.request.entity.RequestStatus;
import kr.astra.request.repository.TestRequestRepository;
import kr.astra.request.service.RequestCommandService;
import kr.astra.request.service.RequestQueryService;
import kr.astra.reservation.dto.ReservationInputs;
import kr.astra.reservation.entity.Reservation;
import kr.astra.reservation.entity.ReservationStatus;
import kr.astra.reservation.repository.ReservationRepository;
import kr.astra.review.service.ReviewService;
import kr.astra.user.entity.User;
import kr.astra.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 시연용 예약 (프론트 2주 일정표). 서버가 뜰 때와 매일 0시 10분에, manager1 계정 조직의 장비 앞 3대에
 * "오늘부터 14일 안" 예약 4건(CONFIRMED 2, PROPOSED 2)이 있도록 맞춥니다.
 * <ul>
 *   <li>처음에는 실제 서비스 흐름(company 의뢰 작성 → 제출 → manager1 검토·통과 → 일정 제안 → 확정)으로 만들고,
 *       예약에 external_system='ASTRA_DEMO', external_ref='slot-N' 을 붙여 둡니다.</li>
 *   <li>다음부터는 그 예약이 14일 창 밖(지난 날짜)이면 날짜만 오늘 기준으로 옮깁니다. 의뢰가 이미 완료·취소돼
 *       옮길 수 없으면 새로 만듭니다.</li>
 *   <li>시연 계정(company·manager1 @astra.demo)이나 장비가 없으면 아무것도 하지 않음. 끄기: ASTRA_DEMO_RESERVATIONS=false</li>
 * </ul>
 * 한 칸이 실패해도(겹치는 실제 예약, 점검 기간 등) 경고 로그만 남기고 다음 칸으로 넘어갑니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoReservationSeeder {

    static final String DEMO_SYSTEM = "ASTRA_DEMO";
    static final String COMPANY_EMAIL = "company@astra.demo";
    static final String MANAGER_EMAIL = "manager1@astra.demo";
    static final int WINDOW_DAYS = 14;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** equipmentIndex: manager1 조직 장비(id 순) 중 몇 번째인지. 장비가 적으면 나머지로 돌려 씀 */
    record Slot(String ref, int equipmentIndex, int dayOffset, int startHour, int hours, boolean confirmed,
                String title, String purpose, String sampleName) {
    }

    static final List<Slot> SLOTS = List.of(
            new Slot("slot-1", 0, 2, 9, 8, true, "위성 전장품 진동시험", "발사 환경 진동 내구성 확인", "위성 탑재 전장품"),
            new Slot("slot-2", 1, 4, 10, 5, false, "드론 프레임 내구 시험", "비행 하중 반복 내구성 확인", "드론 탄소 프레임"),
            new Slot("slot-3", 0, 7, 9, 9, true, "탑재체 구조물 진동시험", "탑재체 구조 건전성 확인", "탑재체 구조 시편"),
            new Slot("slot-4", 2, 10, 13, 4, false, "소형 위성 부품 환경시험", "궤도 환경 적합성 확인", "큐브위성 부품"));

    private final UserRepository userRepository;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentService equipmentService;
    private final ReservationRepository reservationRepository;
    private final TestRequestRepository requestRepository;
    private final RequestCommandService commandService;
    private final RequestQueryService queryService;
    private final ReviewService reviewService;
    private final ReservationService reservationService;
    private final TransactionTemplate transactionTemplate;

    @Value("${astra.demo.reservations.enabled:false}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        refreshIfEnabled();
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Seoul")
    public void daily() {
        refreshIfEnabled();
    }

    private void refreshIfEnabled() {
        if (!enabled) {
            return;
        }
        try {
            int ready = refresh();
            log.info("시연 예약 {}건이 오늘부터 {}일 안에 있습니다.", ready, WINDOW_DAYS);
        } catch (RuntimeException e) {
            log.warn("시연 예약을 준비하지 못했습니다: {}", e.toString());
        }
    }

    /** @return 14일 창 안에 준비된 시연 예약 수 */
    public int refresh() {
        Optional<User> company = userRepository.findByEmail(COMPANY_EMAIL).filter(User::isActive);
        Optional<User> manager = userRepository.findByEmail(MANAGER_EMAIL).filter(User::isActive);
        if (company.isEmpty() || manager.isEmpty() || manager.get().getOrganizationId() == null) {
            log.info("시연 계정이 없어 시연 예약을 건너뜁니다.");
            return 0;
        }
        List<Long> equipmentIds = equipmentRepository.findAllByInstitutionIdAndActiveTrue(
                        manager.get().getOrganizationId(), PageRequest.of(0, 3, Sort.by("id")))
                .map(Equipment::getId).getContent();
        if (equipmentIds.isEmpty()) {
            log.info("manager1 조직에 장비가 없어 시연 예약을 건너뜁니다.");
            return 0;
        }
        CurrentUser requester = actor(company.get());
        CurrentUser provider = actor(manager.get());
        LocalDate today = LocalDate.now(KST);
        int ready = 0;
        for (Slot slot : SLOTS) {
            try {
                Boolean ok = transactionTemplate.execute(status -> prepare(slot, today, equipmentIds, requester, provider));
                if (Boolean.TRUE.equals(ok)) {
                    ready++;
                }
            } catch (RuntimeException e) {
                log.warn("시연 예약 {} 을(를) 준비하지 못했습니다: {}", slot.ref(), e.toString());
            }
        }
        return ready;
    }

    private boolean prepare(Slot slot, LocalDate today, List<Long> equipmentIds, CurrentUser requester,
                            CurrentUser provider) {
        ZonedDateTime start = today.plusDays(slot.dayOffset()).atTime(slot.startHour(), 0).atZone(KST);
        OffsetDateTime startAt = start.toOffsetDateTime();
        OffsetDateTime endAt = start.plusHours(slot.hours()).toOffsetDateTime();

        Optional<Reservation> existing = reservationRepository
                .findFirstByExternalSystemAndExternalRefOrderByIdDesc(DEMO_SYSTEM, slot.ref());
        if (existing.isPresent() && stillUsable(existing.get())) {
            Reservation r = existing.get();
            if (inWindow(r.getStartAt(), today)) {
                return true;
            }
            r.moveDemo(startAt, endAt);
            reservationRepository.saveAndFlush(r); // 확정 예약끼리 겹치면 DB 제약이 거부 → 이 칸만 실패
            return true;
        }
        create(slot, equipmentIds.get(slot.equipmentIndex() % equipmentIds.size()), startAt, endAt, requester,
                provider);
        return true;
    }

    /** 예약이 아직 열려 있거나 확정 상태이고, 의뢰도 그에 맞는 상태(일정 조율 중·확정)일 때만 날짜를 옮겨 다시 씀 */
    private boolean stillUsable(Reservation r) {
        RequestStatus requestStatus = requestRepository.findById(r.getRequestId())
                .map(kr.astra.request.entity.TestRequest::getStatus).orElse(null);
        if (r.getStatus() == ReservationStatus.CONFIRMED) {
            return requestStatus == RequestStatus.CONFIRMED;
        }
        return r.isOpen() && requestStatus == RequestStatus.SCHEDULING;
    }

    private static boolean inWindow(OffsetDateTime startAt, LocalDate today) {
        OffsetDateTime from = OffsetDateTime.now();
        OffsetDateTime to = today.plusDays(WINDOW_DAYS).atStartOfDay(KST).toOffsetDateTime();
        return !startAt.isBefore(from) && startAt.isBefore(to);
    }

    /** 실제 화면과 같은 순서로 의뢰를 진행해 일정까지 만듦 (알림·상태 이력도 그대로 남음) */
    private void create(Slot slot, Long equipmentId, OffsetDateTime startAt, OffsetDateTime endAt,
                        CurrentUser requester, CurrentUser provider) {
        Long requestId = commandService.create(requester, new RequestInputs.Create(equipmentId, null, null,
                slot.title()));
        Detail detail = queryService.detail(requester, requestId);
        Map<String, Object> content = new LinkedHashMap<>(detail.workingRevision() != null
                ? detail.workingRevision().content() : detail.revision().content());
        EquipmentDetailResponse equipment = equipmentService.getDetail(equipmentId);
        Map<String, Object> test = new LinkedHashMap<>();
        if (!equipment.testTypes().isEmpty()) {
            test.put("testTypeCode", equipment.testTypes().get(0));
        }
        test.put("purpose", slot.purpose());
        content.put("test", test);
        content.put("sample", new LinkedHashMap<>(Map.of("name", slot.sampleName(), "quantity", 1)));
        content.put("accounting", new LinkedHashMap<>(Map.of("name", "김회계", "phone", "02-000-0000",
                "email", "account@example.com")));
        content.put("schedule", new LinkedHashMap<>(Map.of(
                "desiredStartDate", startAt.atZoneSameInstant(KST).toLocalDate().toString(),
                "desiredEndDate", endAt.atZoneSameInstant(KST).toLocalDate().toString())));
        commandService.saveDraft(requester, requestId, new RequestInputs.SaveDraft(null, slot.title(), content,
                "시연 일정 데이터"));
        commandService.submit(requester, requestId, null);
        reviewService.startReview(provider, requestId, null);
        reviewService.accept(provider, requestId, null);
        Long reservationId = reservationService.propose(provider, requestId,
                new ReservationInputs.Propose(startAt, endAt, "시연 일정", null)).reservation().id();
        if (slot.confirmed()) {
            reservationService.confirm(provider, reservationId, null);
        }
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        reservation.markDemo(DEMO_SYSTEM, slot.ref());
        reservationRepository.saveAndFlush(reservation);
        log.info("시연 예약 {} 생성: 의뢰 {}, 장비 {}, {} ~ {}", slot.ref(), requestId, equipmentId, startAt, endAt);
    }

    private static CurrentUser actor(User user) {
        return new CurrentUser(user.getId(), user.getRole(), user.getOrganizationId());
    }
}
