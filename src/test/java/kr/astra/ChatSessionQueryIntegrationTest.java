package kr.astra;

import static org.assertj.core.api.Assertions.*;

import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.astra.chat.dto.ChatSessionSummary;
import kr.astra.chat.repository.ChatSessionRepository;
import kr.astra.chat.service.ChatPersistence;
import kr.astra.chat.service.ChatSessionQuery;
import kr.astra.dashboard.dto.DashboardResponses.CompanySummary;
import kr.astra.dashboard.service.DashboardService;
import kr.astra.global.security.CurrentUser;
import kr.astra.llm.service.ConditionExtractionService.Extraction;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/** 실제 PostgreSQL에서 요약 projection, 활동 시각 저장, 대시보드 최대 5개 연결을 검증합니다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ChatSessionQueryIntegrationTest {
    @Autowired ChatSessionQuery query;
    @Autowired ChatPersistence chats;
    @Autowired ChatSessionRepository sessions;
    @Autowired DashboardService dashboard;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    private CurrentUser owner;
    private final OffsetDateTime past = OffsetDateTime.parse("2020-01-01T00:00:00Z");

    @BeforeEach void login() {
        Long id = jdbc.queryForObject("select id from users where email = 'company@astra.demo'", Long.class);
        owner = new CurrentUser(id, UserRole.COMPANY_USER, null);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(owner, null, List.of()));
    }

    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    @Test void recentChatsAreOwnerScopedLimitedAndOrderedByActivityThenId() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Long id = chats.create(owner, "상담 " + i).id();
            ids.add(id);
            backdate(id);
        }
        // 생성은 가장 오래된 상담이지만 활동은 가장 최근이어야 합니다.
        sessions.recordActivity(ids.getFirst(), past.plusDays(1));
        Long otherId = jdbc.queryForObject("select id from users where email = 'admin@astra.demo'", Long.class);
        chats.create(new CurrentUser(otherId, UserRole.ADMIN, null), "다른 사용자 상담");
        entityManager.clear();

        var summary = (CompanySummary) dashboard.summary(owner);
        assertThat(summary.dataAvailable().chats()).isTrue();
        assertThat(summary.recentChats()).extracting(ChatSessionSummary::id)
                .containsExactly(ids.get(0), ids.get(6), ids.get(5), ids.get(4), ids.get(3));
        assertThat(summary.recentChats().getFirst().updatedAt()).isEqualTo(past.plusDays(1));
        assertThat(query.findRecent(owner.userId(), 2)).hasSize(2);
    }

    @Test void messagesAndRequirementSavePersistActivityWhileFailureDoesNot() {
        Long id = chats.create(owner, "진동시험").id();
        backdate(id);
        var turn = chats.begin(owner, id, "진동시험 조건을 정리해 주세요");
        assertThat(activity(id)).isAfter(past);

        backdate(id);
        chats.complete(owner, turn, "시료 질량을 알려 주세요", Map.of());
        assertThat(activity(id)).isAfter(past);

        backdate(id);
        chats.saveRequirement(owner, id, chats.transcript(owner, id),
                new Extraction("VIBRATION", List.of(), Map.of()));
        assertThat(activity(id)).isAfter(past);

        backdate(id);
        chats.fail(owner, turn);
        assertThat(activity(id)).isEqualTo(past);
    }

    private void backdate(Long id) {
        sessions.recordActivity(id, past);
        entityManager.clear();
    }

    private OffsetDateTime activity(Long id) {
        entityManager.flush();
        entityManager.clear();
        return sessions.findById(id).orElseThrow().getUpdatedAt();
    }
}
