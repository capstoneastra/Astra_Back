package kr.astra.equipment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.LongStream;
import kr.astra.equipment.repository.EquipmentJdbcRepository;
import kr.astra.equipment.repository.EquipmentJdbcRepository.ReminderRow;
import kr.astra.notification.entity.NotificationType;
import kr.astra.notification.service.NotificationService;
import kr.astra.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionOperations;

/** 90일 경과 장비 알림: 알림 구현(BE-B)이 있을 때만, 기관마다 활성 담당자에게 요약 한 건씩 */
class EquipmentUpdateReminderTest {

    private final EquipmentJdbcRepository jdbc = mock(EquipmentJdbcRepository.class);
    private final UserService userService = mock(UserService.class);
    private final NotificationService notifier = mock(NotificationService.class);

    @Test
    void 기관마다_담당자에게_요약_한_건씩_보내고_담당자는_기관당_한_번만_찾는다() {
        when(jdbc.findReminderInstitutionIds(90)).thenReturn(List.of(2L, 6L));
        when(jdbc.countJustOutdated(90, 2L)).thenReturn(3L);
        when(jdbc.findJustOutdated(90, 2L, EquipmentUpdateReminder.CHUNK)).thenReturn(List.of(
                new ReminderRow(48L, 2L, "진동시험기"), new ReminderRow(49L, 2L, "온습도챔버"),
                new ReminderRow(50L, 2L, "충격시험기")));
        when(jdbc.countJustOutdated(90, 6L)).thenReturn(1L);
        when(jdbc.findJustOutdated(90, 6L, EquipmentUpdateReminder.CHUNK))
                .thenReturn(List.of(new ReminderRow(60L, 6L, "열진공챔버")));
        when(userService.findActiveManagerIds(2L)).thenReturn(List.of(3L, 9L));
        when(userService.findActiveManagerIds(6L)).thenReturn(List.of(5L));

        int sent = reminder(notifier).remind();

        assertThat(sent).isEqualTo(3);
        verify(notifier).notify(eq(3L), eq(NotificationType.EQUIPMENT_UPDATE_REQUIRED),
                argThat(t -> t.contains("3대") && t.contains("진동시험기")), eq("/institutions/me/equipment"), isNull());
        verify(notifier).notify(eq(9L), eq(NotificationType.EQUIPMENT_UPDATE_REQUIRED), anyString(),
                eq("/institutions/me/equipment"), isNull());
        verify(notifier).notify(eq(5L), eq(NotificationType.EQUIPMENT_UPDATE_REQUIRED),
                argThat(t -> t.contains("1대") && t.contains("열진공챔버")), anyString(), isNull());
        verify(notifier, times(3)).notify(any(), any(), anyString(), anyString(), isNull());
        verify(userService, times(1)).findActiveManagerIds(2L);
        verify(jdbc).markAvailabilityReminded(List.of(48L, 49L, 50L));
        verify(jdbc).markAvailabilityReminded(List.of(60L));
    }

    @Test
    void 대상이_많으면_나눠서_잠그고_기록하지만_알림은_기관당_한_번() {
        int chunk = EquipmentUpdateReminder.CHUNK;
        List<ReminderRow> first = rows(1, chunk);
        List<ReminderRow> second = rows(chunk + 1, chunk + 20);
        when(jdbc.findReminderInstitutionIds(90)).thenReturn(List.of(2L));
        when(jdbc.countJustOutdated(90, 2L)).thenReturn((long) chunk + 20);
        when(jdbc.findJustOutdated(90, 2L, chunk)).thenReturn(first, second);
        when(userService.findActiveManagerIds(2L)).thenReturn(List.of(3L));

        assertThat(reminder(notifier).remind()).isEqualTo(1);

        verify(notifier, times(1)).notify(eq(3L), eq(NotificationType.EQUIPMENT_UPDATE_REQUIRED),
                argThat(t -> t.contains((chunk + 20) + "대") && t.contains(" 외 ")), anyString(), isNull());
        verify(jdbc).markAvailabilityReminded(first.stream().map(ReminderRow::equipmentId).toList());
        verify(jdbc).markAvailabilityReminded(second.stream().map(ReminderRow::equipmentId).toList());
        verify(jdbc, times(2)).findJustOutdated(90, 2L, chunk);
    }

    @Test
    void 담당자가_없으면_잠그지도_발송완료로_기록하지도_않는다() {
        when(jdbc.findReminderInstitutionIds(90)).thenReturn(List.of(2L));
        when(userService.findActiveManagerIds(2L)).thenReturn(List.of());

        assertThat(reminder(notifier).remind()).isZero();

        verify(jdbc, never()).findJustOutdated(anyInt(), anyLong(), anyInt());
        verify(jdbc, never()).markAvailabilityReminded(any());
        verify(notifier, never()).notify(any(), any(), any(), any(), any());
    }

    @Test
    void 다른_작업이_모두_잠가_가져온_장비가_없으면_보내지_않는다() {
        when(jdbc.findReminderInstitutionIds(90)).thenReturn(List.of(2L));
        when(jdbc.countJustOutdated(90, 2L)).thenReturn(3L);
        when(jdbc.findJustOutdated(90, 2L, EquipmentUpdateReminder.CHUNK)).thenReturn(List.of());
        when(userService.findActiveManagerIds(2L)).thenReturn(List.of(3L));

        assertThat(reminder(notifier).remind()).isZero();
        verify(notifier, never()).notify(any(), any(), any(), any(), any());
        verify(jdbc, never()).markAvailabilityReminded(any());
    }

    @Test
    void 알림_구현이_없으면_아무것도_안_한다() {
        assertThat(reminder(null).remind()).isZero();
        verify(jdbc, never()).findReminderInstitutionIds(anyInt());
    }

    @SuppressWarnings("unchecked")
    private EquipmentUpdateReminder reminder(NotificationService service) {
        ObjectProvider<NotificationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return new EquipmentUpdateReminder(jdbc, userService, provider, TransactionOperations.withoutTransaction());
    }

    private static List<ReminderRow> rows(long from, long to) {
        return LongStream.rangeClosed(from, to).mapToObj(i -> new ReminderRow(i, 2L, "장비" + i)).toList();
    }
}
