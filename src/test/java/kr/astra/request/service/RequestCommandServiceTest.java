package kr.astra.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import kr.astra.equipment.dto.EquipmentDetailResponse;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.global.security.CurrentUser;
import kr.astra.matching.service.MatchingService;
import kr.astra.request.dto.CreateDraftCommand;
import kr.astra.request.dto.RequestInputs;
import kr.astra.requirement.service.RequirementApplicationService;
import kr.astra.user.dto.UserMeResponse;
import kr.astra.user.entity.UserRole;
import kr.astra.user.service.UserService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** POST /test-requests 는 장비 상세를 한 번만 조회하고 그 결과를 의뢰 생성에 넘겨야 합니다 */
class RequestCommandServiceTest {

    @Test
    void 의뢰_생성은_장비를_한_번만_조회한다() {
        TestRequestServiceImpl drafts = mock(TestRequestServiceImpl.class);
        EquipmentService equipmentService = mock(EquipmentService.class);
        UserService userService = mock(UserService.class);
        RequestCommandService service = new RequestCommandService(drafts, mock(RequestWorkflow.class),
                new RequestContent(JsonMapper.builder().build()), equipmentService, userService,
                mock(RequirementApplicationService.class), mock(MatchingService.class));

        EquipmentDetailResponse equipment = mock(EquipmentDetailResponse.class);
        when(equipment.id()).thenReturn(48L);
        when(equipment.name()).thenReturn("진동시험기");
        when(equipmentService.getDetail(48L)).thenReturn(equipment);
        when(userService.getMe(anyLong())).thenReturn(mock(UserMeResponse.class));
        when(drafts.createDraft(any(CreateDraftCommand.class), same(equipment))).thenReturn(7L);

        Long id = service.create(new CurrentUser(1L, UserRole.COMPANY_USER, 9L),
                new RequestInputs.Create(48L, null, null, null));

        assertThat(id).isEqualTo(7L);
        verify(equipmentService, times(1)).getDetail(48L);
        verify(drafts).createDraft(any(CreateDraftCommand.class), same(equipment));
    }
}
