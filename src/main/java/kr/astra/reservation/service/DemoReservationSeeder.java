package kr.astra.reservation.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.astra.equipment.dto.EquipmentDetailResponse;
import kr.astra.equipment.entity.Equipment;
import kr.astra.equipment.repository.EquipmentRepository;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
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
 * 시연용 예약 (대시보드 캘린더·일정표). 서버가 뜰 때와 매일 0시 10분에, lab1 계정 조직의 장비 앞 3대에
 * "오늘부터 +1~+21일" 예약 9건(CONFIRMED 7, PROPOSED 2)이 있도록 맞춥니다.
 * <ul>
 *   <li>처음에는 실제 서비스 흐름(company 의뢰 작성 → 제출 → lab1 검토·통과 → 일정 제안 → 확정)으로 만들고,
 *       예약에 external_system='ASTRA_DEMO', external_ref='slot-N' 을 붙여 둡니다.</li>
 *   <li>하루짜리와 2~3일짜리를 섞고, 같은 날 다른 장비 2건(slot-3·slot-6)도 둡니다.
 *       slot-5 는 결과까지 등록해 두어 시연에서 "시험 완료"를 바로 누를 수 있습니다.</li>
 *   <li>다음부터는 매번 날짜를 오늘 기준으로 다시 맞춥니다 (늦은 일정부터 옮겨 시연 예약끼리 겹치지 않게).
 *       의뢰가 이미 완료·취소돼 옮길 수 없으면 새로 만듭니다.</li>
 *   <li>시연 계정(company·lab1 @astra.kr)이나 장비가 없으면 아무것도 하지 않음. 끄기: ASTRA_DEMO_RESERVATIONS=false</li>
 * </ul>
 * 한 칸이 실패해도(겹치는 실제 예약, 점검 기간 등) 경고 로그만 남기고 다음 칸으로 넘어갑니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoReservationSeeder {

    static final String DEMO_SYSTEM = "ASTRA_DEMO";
    static final String COMPANY_EMAIL = "company@astra.kr";
    static final String MANAGER_EMAIL = "lab1@astra.kr";
    static final int WINDOW_DAYS = 22;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * equipmentIndex: lab1 조직 장비(id 순) 중 몇 번째인지. 장비가 적으면 나머지로 돌려 씀.
     * hours 가 24를 넘으면 여러 날에 걸친 일정 (예: 9시 시작 57시간 = 사흘째 18시 종료).
     * withResult: 확정 후 시험 결과까지 등록 ("시험 완료" 시연용).
     * variant: 제목 고르기 (같은 장비의 일정끼리 0·1·2 로 달리 함). 문구는 장비 시험유형에 맞춰 {@link DemoRequestThemes}
     */
    record Slot(String ref, int equipmentIndex, int dayOffset, int startHour, int hours, boolean confirmed,
                boolean withResult, int variant) {
    }

    /** 확정 예약끼리는 같은 장비에서 겹치지 않게 배치 (DB 제약) */
    static final List<Slot> SLOTS = List.of(
            new Slot("slot-1", 0, 2, 9, 8, true, false, 0),
            new Slot("slot-2", 1, 4, 10, 5, false, false, 0),
            new Slot("slot-3", 0, 7, 9, 9, true, false, 1),
            new Slot("slot-4", 2, 10, 13, 4, false, false, 0),
            new Slot("slot-5", 1, 1, 10, 6, true, true, 1),
            new Slot("slot-6", 2, 7, 13, 4, true, false, 1),
            new Slot("slot-7", 1, 12, 9, 57, true, false, 2),
            new Slot("slot-8", 2, 15, 9, 33, true, false, 2),
            new Slot("slot-9", 0, 19, 9, 57, true, false, 2));

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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

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
            log.info("lab1 조직에 장비가 없어 시연 예약을 건너뜁니다.");
            return 0;
        }
        CurrentUser requester = actor(company.get());
        CurrentUser provider = actor(manager.get());
        LocalDate today = LocalDate.now(KST);
        int ready = 0;
        // 1) 이미 있는 예약을 오늘 기준으로 옮김. 늦은 일정부터 옮겨야 아직 안 옮긴 다른 시연 예약과 겹치지 않음
        List<Slot> toCreate = new ArrayList<>();
        List<Slot> latestFirst = SLOTS.stream()
                .sorted(Comparator.comparing((Slot s) -> startOf(s, today)).reversed()).toList();
        for (Slot slot : latestFirst) {
            try {
                Boolean moved = transactionTemplate.execute(status -> moveIfExists(slot, today));
                if (Boolean.TRUE.equals(moved)) {
                    ready++;
                } else {
                    toCreate.add(slot);
                }
            } catch (RuntimeException e) {
                log.warn("시연 예약 {} 을(를) 옮기지 못했습니다: {}", slot.ref(), e.toString());
            }
        }
        // 2) 없거나 다시 쓸 수 없는 칸은 실제 흐름으로 새로 만듦 (이른 일정부터)
        toCreate.sort(Comparator.comparing((Slot s) -> startOf(s, today)));
        for (Slot slot : toCreate) {
            if (createWithRetry(slot, today, equipmentIds.get(slot.equipmentIndex() % equipmentIds.size()),
                    requester, provider)) {
                ready++;
            }
        }
        return ready;
    }

    /**
     * 그 시간에 이미 확정 예약이 있으면 (예: 시험 완료된 이전 시연 의뢰의 예약) 하루씩 미뤄 최대 3번 더 시도.
     * 다음 실행 때 원래 날짜로 다시 맞춰 봅니다.
     */
    private boolean createWithRetry(Slot slot, LocalDate today, Long equipmentId, CurrentUser requester,
                                    CurrentUser provider) {
        for (int extraDays = 0; extraDays <= 3; extraDays++) {
            LocalDate day = today.plusDays(extraDays);
            try {
                transactionTemplate.executeWithoutResult(status -> create(slot, equipmentId, startOf(slot, day),
                        endOf(slot, day), requester, provider));
                return true;
            } catch (BusinessException e) {
                if (e.getErrorCode() != ErrorCode.RESERVATION_OVERLAP) {
                    log.warn("시연 예약 {} 을(를) 만들지 못했습니다: {}", slot.ref(), e.toString());
                    return false;
                }
            } catch (RuntimeException e) {
                log.warn("시연 예약 {} 을(를) 만들지 못했습니다: {}", slot.ref(), e.toString());
                return false;
            }
        }
        log.warn("시연 예약 {} 을(를) 만들지 못했습니다: 이후 며칠이 모두 확정 예약과 겹침", slot.ref());
        return false;
    }

    private static OffsetDateTime startOf(Slot slot, LocalDate today) {
        return today.plusDays(slot.dayOffset()).atTime(slot.startHour(), 0).atZone(KST).toOffsetDateTime();
    }

    private static OffsetDateTime endOf(Slot slot, LocalDate today) {
        ZonedDateTime start = today.plusDays(slot.dayOffset()).atTime(slot.startHour(), 0).atZone(KST);
        return start.plusHours(slot.hours()).toOffsetDateTime();
    }

    /** @return 다시 쓸 수 있는 예약이 있어 날짜를 맞췄으면 true, 새로 만들어야 하면 false */
    private boolean moveIfExists(Slot slot, LocalDate today) {
        Optional<Reservation> existing = reservationRepository
                .findFirstByExternalSystemAndExternalRefOrderByIdDesc(DEMO_SYSTEM, slot.ref());
        if (existing.isEmpty() || !stillUsable(existing.get())) {
            return false;
        }
        Reservation r = existing.get();
        syncText(r.getRequestId(), themeOf(slot, equipmentService.getDetail(r.getEquipmentId())));
        OffsetDateTime startAt = startOf(slot, today);
        OffsetDateTime endAt = endOf(slot, today);
        if (r.getStartAt().isEqual(startAt) && r.getEndAt().isEqual(endAt)) {
            return true;
        }
        r.moveDemo(startAt, endAt);
        reservationRepository.saveAndFlush(r); // 실제 확정 예약과 겹치면 DB 제약이 거부 → 이 칸만 실패
        return true;
    }

    private static DemoRequestThemes.Theme themeOf(Slot slot, EquipmentDetailResponse equipment) {
        String testType = equipment.testTypes().isEmpty() ? null : equipment.testTypes().get(0);
        return DemoRequestThemes.of(testType, equipment.name(), slot.variant());
    }

    /**
     * 이미 만든 시연 의뢰의 제목·목적·시료명·결과 문구를 장비에 맞는 문구로 맞춤
     * (예전 버전이 장비와 상관없이 "진동시험" 제목을 붙였던 것 정리). 같으면 아무것도 바꾸지 않음
     */
    private void syncText(Long requestId, DemoRequestThemes.Theme theme) {
        int changed = jdbc.update("UPDATE test_requests SET title = ? WHERE id = ? AND title IS DISTINCT FROM ?",
                theme.title(), requestId, theme.title());
        jdbc.update("""
                UPDATE test_request_revisions
                SET content = jsonb_set(jsonb_set(content, '{test,purpose}', to_jsonb(?::text), true),
                                        '{sample,name}', to_jsonb(?::text), true)
                WHERE request_id = ? AND content -> 'test' IS NOT NULL AND content -> 'sample' IS NOT NULL
                  AND (content #>> '{test,purpose}' IS DISTINCT FROM ? OR content #>> '{sample,name}' IS DISTINCT FROM ?)
                """, theme.purpose(), theme.sampleName(), requestId, theme.purpose(), theme.sampleName());
        jdbc.update("UPDATE test_results SET summary = ? WHERE request_id = ? AND summary IS DISTINCT FROM ?",
                theme.resultSummary(), requestId, theme.resultSummary());
        if (changed > 0) {
            log.info("시연 의뢰 {} 제목을 장비에 맞게 바꿈: {}", requestId, theme.title());
        }
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

    /** 실제 화면과 같은 순서로 의뢰를 진행해 일정까지 만듦 (알림·상태 이력도 그대로 남음) */
    private void create(Slot slot, Long equipmentId, OffsetDateTime startAt, OffsetDateTime endAt,
                        CurrentUser requester, CurrentUser provider) {
        EquipmentDetailResponse equipment = equipmentService.getDetail(equipmentId);
        DemoRequestThemes.Theme theme = themeOf(slot, equipment);
        Long requestId = commandService.create(requester, new RequestInputs.Create(equipmentId, null, null,
                theme.title()));
        Detail detail = queryService.detail(requester, requestId);
        Map<String, Object> content = new LinkedHashMap<>(detail.workingRevision() != null
                ? detail.workingRevision().content() : detail.revision().content());
        Map<String, Object> test = new LinkedHashMap<>();
        if (!equipment.testTypes().isEmpty()) {
            test.put("testTypeCode", equipment.testTypes().get(0));
        }
        test.put("purpose", theme.purpose());
        content.put("test", test);
        content.put("sample", new LinkedHashMap<>(Map.of("name", theme.sampleName(), "quantity", 1)));
        content.put("accounting", new LinkedHashMap<>(Map.of("name", "김회계", "phone", "02-000-0000",
                "email", "account@example.com")));
        content.put("schedule", new LinkedHashMap<>(Map.of(
                "desiredStartDate", startAt.atZoneSameInstant(KST).toLocalDate().toString(),
                "desiredEndDate", endAt.atZoneSameInstant(KST).toLocalDate().toString())));
        commandService.saveDraft(requester, requestId, new RequestInputs.SaveDraft(null, theme.title(), content,
                "시연 일정 데이터"));
        commandService.submit(requester, requestId, null);
        reviewService.startReview(provider, requestId, null);
        reviewService.accept(provider, requestId, null);
        Long reservationId = reservationService.propose(provider, requestId,
                new ReservationInputs.Propose(startAt, endAt, "시연 일정", null)).reservation().id();
        if (slot.confirmed()) {
            reservationService.confirm(provider, reservationId, null);
            if (slot.withResult()) {
                commandService.registerResult(provider, requestId, new RequestInputs.Result(
                        theme.resultSummary(), "ASTRA-DEMO-" + requestId, null));
            }
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
