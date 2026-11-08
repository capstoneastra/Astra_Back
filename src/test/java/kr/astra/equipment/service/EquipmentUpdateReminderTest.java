package kr.astra.equipment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import kr.astra.equipment.repository.EquipmentJdbcRepository;
import kr.astra.equipment.repository.EquipmentJdbcRepository.ReminderRow;
import kr.astra.notification.entity.NotificationType;
import kr.astra.notification.service.NotificationService;
import kr.astra.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 90일 경과 장비 알림: 알림 구현(BE-B)이 있을 때만, 소속 기관 활성 담당자 전원에게 */
class EquipmentUpdateReminderTest {

    private final EquipmentJdbcRepository jdbc = mock(EquipmentJdbcRepository.class);
    private final UserService userService = mock(UserService.class);

    @Test
    @SuppressWarnings("unchecked")
    void 알림_구현이_있으면_담당자마다_한_건씩_보낸다() {
        NotificationService notifier = mock(NotificationService.class);
        ObjectProvider<NotificationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(notifier);
        when(jdbc.findJustOutdated(90)).thenReturn(List.of(new ReminderRow(48L, 2L, "진동시험기")));
        when(userService.findActiveManagerIds(2L)).thenReturn(List.of(3L, 9L));

        int sent = new EquipmentUpdateReminder(jdbc, userService, provider).remind();

        assertThat(sent).isEqualTo(2);
        verify(notifier).notify(eq(3L), eq(NotificationType.EQUIPMENT_UPDATE_REQUIRED), anyString(),
                eq("/institutions/me/equipment"), isNull());
        verify(notifier, times(2)).notify(any(), any(), anyString(), anyString(), isNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 알림_구현이_없으면_아무것도_안_한다() {
        ObjectProvider<NotificationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        assertThat(new EquipmentUpdateReminder(jdbc, userService, provider).remind()).isZero();
        verify(jdbc, never()).findJustOutdated(90);
    }
}
