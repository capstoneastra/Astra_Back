package kr.astra.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 작업 켜기 (예: EquipmentUpdateReminder 매일 오전 9시 장비 갱신 알림).
 * 주기 작업을 추가하면 cron 에 zone = "Asia/Seoul" 을 꼭 적어 주세요.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
