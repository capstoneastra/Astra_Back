package kr.astra.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.List;
import kr.astra.chat.dto.ChatSessionSummary;
import kr.astra.chat.repository.ChatSessionRepository;
import kr.astra.chat.service.ChatSessionQuery;
import kr.astra.chat.service.ChatSessionQueryService;
import kr.astra.dashboard.dto.DashboardResponses.CompanySummary;
import kr.astra.dashboard.service.DashboardService;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.security.CurrentUser;
import kr.astra.organization.service.OrganizationService;
import kr.astra.testtype.service.TestTypeService;
import kr.astra.user.entity.UserRole;
import kr.astra.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ChatSessionQueryTest {
    private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    private final ChatSessionQuery query = new ChatSessionQueryService(sessions);
    private final CurrentUser owner = new CurrentUser(7L, UserRole.COMPANY_USER, null);

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void ownerQueryUsesActivityOrderStableTieBreakAndRequestedLimit() {
        login(owner);
        var page = PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "updatedAt", "id"));
        var summary = new ChatSessionSummary(11L, "진동시험", OffsetDateTime.now());
        when(sessions.findRecentSummaries(7L, page)).thenReturn(List.of(summary));

        assertThat(query.findRecent(7L, 5)).containsExactly(summary);
        verify(sessions).findRecentSummaries(7L, page);
        verifyNoMoreInteractions(sessions);
    }

    @Test void anonymousOrUnauthenticatedPrincipalCannotQuery() {
        assertError(() -> query.findRecent(7L, 5), ErrorCode.AUTH_REQUIRED);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated(owner, null));
        assertError(() -> query.findRecent(7L, 5), ErrorCode.AUTH_REQUIRED);
        verifyNoInteractions(sessions);
    }

    @Test void anotherCompanyOrInstitutionCannotReadOwnersChats() {
        login(new CurrentUser(8L, UserRole.COMPANY_USER, null));
        assertError(() -> query.findRecent(7L, 5), ErrorCode.FORBIDDEN);
        login(new CurrentUser(7L, UserRole.INSTITUTION_MANAGER, 2L));
        assertError(() -> query.findRecent(7L, 5), ErrorCode.FORBIDDEN);
        verifyNoInteractions(sessions);
    }

    @Test void adminMayQueryAnotherOwnersEmptyHistory() {
        login(new CurrentUser(1L, UserRole.ADMIN, null));
        assertThat(query.findRecent(7L, 5)).isEmpty();
        verify(sessions).findRecentSummaries(eq(7L), any());
    }

    @Test void invalidLimitsAndOwnerIdsNeverReachRepository() {
        login(owner);
        for (int limit : new int[] {-1, 0, 101, Integer.MAX_VALUE})
            assertError(() -> query.findRecent(7L, limit), ErrorCode.VALIDATION_FAILED);
        login(new CurrentUser(1L, UserRole.ADMIN, null));
        assertError(() -> query.findRecent(null, 5), ErrorCode.VALIDATION_FAILED);
        assertError(() -> query.findRecent(0L, 5), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(sessions);
    }

    @Test void dashboardDiscoversTheImplementationAndReportsAvailableEvenWhenEmpty() {
        login(owner);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ChatSessionRepository.class, () -> sessions);
            context.registerBean(EquipmentService.class, () -> mock(EquipmentService.class));
            context.registerBean(TestTypeService.class, () -> mock(TestTypeService.class));
            context.registerBean(OrganizationService.class, () -> mock(OrganizationService.class));
            context.registerBean(UserService.class, () -> mock(UserService.class));
            context.register(ChatSessionQueryService.class, DashboardService.class);
            context.refresh();
            assertThat(context.getBeansOfType(ChatSessionQuery.class)).hasSize(1);
            var dashboard = context.getBean(DashboardService.class);
            var empty = (CompanySummary) dashboard.summary(owner);
            assertThat(empty.dataAvailable().chats()).isTrue();
            assertThat(empty.recentChats()).isEmpty();

            var summary = new ChatSessionSummary(11L, "진동시험", OffsetDateTime.now());
            when(sessions.findRecentSummaries(eq(7L), any())).thenReturn(List.of(summary));
            var populated = (CompanySummary) dashboard.summary(owner);
            assertThat(populated.recentChats()).containsExactly(summary);
            assertThat(populated.dataAvailable().chats()).isTrue();
            verify(sessions, times(2)).findRecentSummaries(eq(7L), argThat(p -> p.getPageSize() == 5));
        }
    }

    private void login(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    }

    private void assertError(Runnable action, ErrorCode error) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(error));
    }
}
