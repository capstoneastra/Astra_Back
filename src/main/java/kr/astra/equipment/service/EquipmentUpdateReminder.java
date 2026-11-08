package kr.astra.equipment.service;

import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * 장비 정보 갱신 알림 (api-spec §2-1 흐름 4, §13 EQUIPMENT_UPDATE_REQUIRED).
 * 매일 오전 9시(한국 시간)에 마지막 갱신이 막 90일을 넘긴 장비를 찾아 소속 기관의 활성 담당자에게 알립니다.
 * 하루 한 번 "90~91일 전 갱신" 장비만 보므로 장비마다 한 번만 알림이 갑니다.
 * 알림 저장은 BE-B 의 NotificationService 가 하므로 구현이 없으면 아무것도 하지 않습니다.
 * astra.equipment.update-reminder.enabled=false 로 끌 수 있습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "astra.equipment.update-reminder", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class EquipmentUpdateReminder {

    static final int DAYS = 90;

    private final EquipmentJdbcRepository jdbcRepository;
    private final UserService userService;
    private final ObjectProvider<NotificationService> notificationService;

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void scheduled() {
        int sent = remind();
        if (sent > 0) {
            log.info("장비 정보 갱신 알림 {}건 발송", sent);
        }
    }

    /** @return 보낸 알림 수 (알림 구현이 없으면 0) */
    @Transactional
    public int remind() {
        NotificationService notifier = notificationService.getIfAvailable();
        if (notifier == null) {
            return 0;
        }
        List<ReminderRow> targets = jdbcRepository.findJustOutdated(DAYS);
        int sent = 0;
        for (ReminderRow e : targets) {
            for (Long managerId : userService.findActiveManagerIds(e.institutionId())) {
                notifier.notify(managerId, NotificationType.EQUIPMENT_UPDATE_REQUIRED,
                        "장비 정보를 확인해 주세요: " + e.name() + " (마지막 갱신 " + DAYS + "일 경과)",
                        "/institutions/me/equipment", null);
                sent++;
            }
        }
        return sent;
    }
}
