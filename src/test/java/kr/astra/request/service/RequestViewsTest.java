package kr.astra.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import kr.astra.equipment.entity.Equipment;
import kr.astra.equipment.repository.EquipmentRepository;
import kr.astra.organization.service.OrganizationService;
import kr.astra.request.dto.RequestResponses.EquipmentRef;
import kr.astra.request.entity.TestRequest;
import kr.astra.user.service.UserService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 장비 이름은 같은 트랜잭션에서 한 번에 읽어야 합니다 (예전: 장비마다 REQUIRES_NEW 트랜잭션으로 상세 조회 →
 * 커넥션을 하나 더 잡아 동시 요청이 몰리면 풀 고갈).
 */
class RequestViewsTest {

    private final EquipmentRepository equipmentRepository = mock(EquipmentRepository.class);
    private final RequestViews views = new RequestViews(equipmentRepository, mock(OrganizationService.class),
            mock(UserService.class), new RequestContent(JsonMapper.builder().build()));

    @Test
    void 목록의_장비를_한_번에_읽고_비활성이거나_없는_장비는_비활성_장비로_표시한다() {
        Equipment active = equipment(1L, true);
        Equipment inactive = equipment(2L, false);
        when(equipmentRepository.findAllById(any())).thenReturn(List.of(active, inactive));

        RequestViews.Names names = views.names(List.of(request(1L), request(2L), request(3L), request(1L)), List.of());

        verify(equipmentRepository, times(1)).findAllById(argThat(ids -> Set.copyOf(
                StreamSupport.stream(ids.spliterator(), false).toList()).equals(Set.of(1L, 2L, 3L))));
        assertThat(names.equipment(1L)).isEqualTo(new EquipmentRef(1L, "EXT-1", "장비1", true));
        assertThat(names.equipment(2L)).isEqualTo(new EquipmentRef(2L, null, "(비활성 장비)", false));
        assertThat(names.equipment(3L)).isEqualTo(new EquipmentRef(3L, null, "(비활성 장비)", false));
        verify(equipmentRepository, times(1)).findAllById(any()); // 미리 모은 장비는 다시 조회하지 않음
    }

    @Test
    void 대시보드용_장비_이름도_한_번에_읽는다() {
        List<Equipment> found = List.of(equipment(5L, true), equipment(6L, true));
        when(equipmentRepository.findAllById(any())).thenReturn(found);
        RequestViews.Names names = views.equipmentNames(List.of(5L, 6L, 5L));
        assertThat(names.equipment(5L).name()).isEqualTo("장비5");
        assertThat(names.equipment(6L).name()).isEqualTo("장비6");
        verify(equipmentRepository, times(1)).findAllById(any());
    }

    private static Equipment equipment(Long id, boolean active) {
        Equipment e = mock(Equipment.class);
        when(e.getId()).thenReturn(id);
        when(e.getExternalId()).thenReturn("EXT-" + id);
        when(e.getName()).thenReturn("장비" + id);
        when(e.isMock()).thenReturn(true);
        when(e.isActive()).thenReturn(active);
        return e;
    }

    private static TestRequest request(Long equipmentId) {
        TestRequest r = mock(TestRequest.class);
        when(r.getEquipmentId()).thenReturn(equipmentId);
        when(r.getInstitutionId()).thenReturn(10L);
        when(r.getRequesterId()).thenReturn(100L);
        return r;
    }
}
