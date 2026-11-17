package kr.astra.equipment.service;

import java.util.List;
import kr.astra.equipment.entity.Equipment;
import kr.astra.equipment.repository.EquipmentJdbcRepository;
import kr.astra.equipment.repository.EquipmentJdbcRepository.ReminderRow;
import kr.astra.notification.entity.NotificationType;
import kr.astra.notification.service.NotificationService;
import kr.astra.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 장비 정보 갱신 알림 (api-spec §2-1 흐름 4, §13 EQUIPMENT_UPDATE_REQUIRED).
 * 매일 오전 9시(한국 시간)에 상태 확인이 90일을 넘긴 장비를 찾아 소속 기관의 활성 담당자에게 알립니다.
 * 미확인 장비는 생성일부터 계산하며, 확인 주기별 알림 시각을 기록해 중복을 막고 중단 기간도 보충합니다.
 * 기관마다 담당자에게 요약 알림 한 건("N대: A, B, C 외")을 보내고, 장비는 {@link #CHUNK}대씩 나눠
 * 짧은 트랜잭션으로 잠그고 기록합니다 (예약·장비 수정의 행 잠금을 오래 막지 않도록).
 * 알림 저장은 BE-B 의 NotificationService 가 하므로 구현이 없으면 아무것도 하지 않습니다.
 * astra.equipment.update-reminder.enabled=false 로 끌 수 있습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "astra.equipment.update-reminder", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class EquipmentUpdateReminder {

    static final int DAYS = Equipment.UPDATE_INTERVAL_DAYS;
    /** 한 트랜잭션에서 잠그는 최대 장비 수 */
    static final int CHUNK = 100;
    /** 요약 알림에 이름을 적는 장비 수 */
    private static final int NAMES_IN_TITLE = 3;
    private static final String LINK = "/institutions/me/equipment";

    private final EquipmentJdbcRepository jdbcRepository;
    private final UserService userService;
    private final ObjectProvider<NotificationService> notificationService;
    private final TransactionOperations transaction;

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void scheduled() {
        int sent = remind();
        if (sent > 0) {
            log.info("장비 정보 갱신 알림 {}건 발송", sent);
        }
    }

    /** @return 보낸 알림 수 (알림 구현이 없으면 0). 트랜잭션은 덩어리마다 따로 엽니다 */
    public int remind() {
        NotificationService notifier = notificationService.getIfAvailable();
        if (notifier == null) {
            return 0;
        }
        int sent = 0;
        for (Long institutionId : jdbcRepository.findReminderInstitutionIds(DAYS)) {
            List<Long> managers = userService.findActiveManagerIds(institutionId);
            if (managers.isEmpty()) {
                continue; // 담당자가 생기면 다음 실행에 보내도록 기록하지 않음
            }
            sent += remindInstitution(notifier, institutionId, managers);
        }
        return sent;
    }

    /**
     * 한 기관: 첫 덩어리 트랜잭션에서 요약 알림을 보내고(알림과 발송 기록이 함께 커밋), 나머지 덩어리는 기록만.
     * 중간에 실패하면 기록 안 된 장비는 다음 실행에 다시 알림 (빠뜨리지 않는 쪽을 택함).
     */
    private int remindInstitution(NotificationService notifier, Long institutionId, List<Long> managers) {
        int sent = 0;
        boolean first = true;
        while (true) {
            boolean notify = first;
            Integer locked = transaction.execute(status -> {
                long total = notify ? jdbcRepository.countJustOutdated(DAYS, institutionId) : 0;
                List<ReminderRow> rows = jdbcRepository.findJustOutdated(DAYS, institutionId, CHUNK);
                if (rows.isEmpty()) {
                    return 0;
                }
                if (notify) {
                    String title = title(Math.max(total, rows.size()), rows);
                    managers.forEach(managerId -> notifier.notify(managerId,
                            NotificationType.EQUIPMENT_UPDATE_REQUIRED, title, LINK, null));
                }
                jdbcRepository.markAvailabilityReminded(rows.stream().map(ReminderRow::equipmentId).toList());
                return rows.size();
            });
            int n = locked == null ? 0 : locked;
            if (n == 0) {
                return sent;
            }
            if (first) {
                sent += managers.size();
                first = false;
            }
            if (n < CHUNK) {
                return sent;
            }
        }
    }

    /** 예: "가용 상태 확인이 필요한 장비 5대: 진동시험기, 온습도챔버, 충격시험기 외 2대 (상태 확인 90일 경과)" */
    static String title(long total, List<ReminderRow> rows) {
        // 알림 제목은 200자까지라 이름이 길면 줄임
        List<String> names = rows.stream().limit(NAMES_IN_TITLE).map(ReminderRow::name)
                .map(n -> n.length() > 40 ? n.substring(0, 40) + "…" : n).toList();
        long rest = total - names.size();
        return "가용 상태 확인이 필요한 장비 " + total + "대: " + String.join(", ", names)
                + (rest > 0 ? " 외 " + rest + "대" : "") + " (상태 확인 " + DAYS + "일 경과)";
    }
}
