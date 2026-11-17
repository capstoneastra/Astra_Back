package kr.astra.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import kr.astra.chat.service.ChatSessionQuery;
import kr.astra.dashboard.dto.DashboardResponses.AdminSummary;
import kr.astra.equipment.repository.EquipmentJdbcRepository.InstitutionVerificationRow;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.global.security.CurrentUser;
import kr.astra.organization.service.OrganizationService;
import kr.astra.request.service.RequestDashboardQuery;
import kr.astra.testtype.service.TestTypeService;
import kr.astra.user.entity.UserRole;
import kr.astra.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 기관별 장비 수 집계(GROUP BY institution_id)는 한 요청에서 한 번만 */
class DashboardServiceTest {

    private final EquipmentService equipmentService = mock(EquipmentService.class);

    @Test
    @SuppressWarnings("unchecked")
    void 관리자_요약의_전체_장비_수는_기관별_집계에서_구한다() {
        when(equipmentService.countVerificationByInstitution()).thenReturn(List.of(
                new InstitutionVerificationRow(2L, 13, 0), new InstitutionVerificationRow(6L, 7, 2)));
        ObjectProvider<RequestDashboardQuery> requests = mock(ObjectProvider.class);
        ObjectProvider<ChatSessionQuery> chats = mock(ObjectProvider.class);
        DashboardService service = new DashboardService(equipmentService, mock(TestTypeService.class),
                mock(OrganizationService.class), mock(UserService.class), requests, chats);

        AdminSummary summary = (AdminSummary) service.summary(new CurrentUser(1L, UserRole.ADMIN, null), null);

        assertThat(summary.equipmentStats().total()).isEqualTo(20);
        assertThat(summary.institutionVerification()).hasSize(2);
        verify(equipmentService, times(1)).countVerificationByInstitution();
        verify(equipmentService, never()).countByInstitution();
    }
}
