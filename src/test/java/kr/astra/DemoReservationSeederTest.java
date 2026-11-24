package kr.astra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import kr.astra.reservation.service.DemoReservationSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 시연 예약: manager1 조직 장비에 오늘부터 14일 안 예약 4건 (CONFIRMED·PROPOSED 섞어서, 장비 2~3대).
 * 의뢰·예약을 만들기 때문에 이 테스트만의 DB 를 씁니다.
 */
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class, DemoReservationSeederTest.Isolated.class})
@SpringBootTest
class DemoReservationSeederTest {

    /** 이 테스트만의 컨텍스트(=DB)를 쓰기 위한 표시 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Isolated {
    }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    DemoReservationSeeder seeder;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 오늘부터_14일_안에_예약을_만들고_다시_돌려도_늘지_않으며_지난_예약은_옮긴다() {
        assertThat(seeder.refresh()).isEqualTo(4);

        List<Map<String, Object>> rows = demoReservations();
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(r -> r.get("status")).containsOnly("CONFIRMED", "PROPOSED")
                .contains("CONFIRMED", "PROPOSED");
        assertThat(rows.stream().map(r -> r.get("equipment_id")).distinct().count()).isBetween(2L, 3L);
        Long managerOrg = jdbc.queryForObject("SELECT organization_id FROM users WHERE email = 'manager1@astra.demo'",
                Long.class);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("institution_id")).isEqualTo(managerOrg);
            assertInWindow(r.get("start_at"));
            // 의뢰 상태가 예약과 맞음 (확정 예약 → 의뢰 CONFIRMED, 제안 → SCHEDULING)
            assertThat(r.get("request_status")).isEqualTo(
                    "CONFIRMED".equals(r.get("status")) ? "CONFIRMED" : "SCHEDULING");
            assertThat((String) r.get("title")).doesNotContain("[시연]");
        });

        // 다시 돌려도 새 의뢰를 만들지 않음
        long requests = jdbc.queryForObject("SELECT count(*) FROM test_requests", Long.class);
        assertThat(seeder.refresh()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_requests", Long.class)).isEqualTo(requests);

        // 날짜가 지나 14일 창 밖이 되면 오늘 기준으로 옮김
        jdbc.update("""
                UPDATE reservations SET start_at = start_at - interval '30 days', end_at = end_at - interval '30 days'
                WHERE external_system = 'ASTRA_DEMO'""");
        assertThat(seeder.refresh()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_requests", Long.class)).isEqualTo(requests);
        assertThat(demoReservations()).hasSize(4)
                .allSatisfy(r -> assertInWindow(r.get("start_at")));

        // 프론트 기관 대시보드의 다가오는 예약에도 보임
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reservations r JOIN test_requests q ON q.id = r.request_id
                WHERE q.institution_id = ? AND r.status = 'CONFIRMED' AND r.start_at > now()""", Long.class, managerOrg))
                .isEqualTo(2);
    }

    private List<Map<String, Object>> demoReservations() {
        return jdbc.queryForList("""
                SELECT DISTINCT ON (r.external_ref) r.status, r.equipment_id, r.start_at, q.institution_id,
                       q.status AS request_status, q.title
                FROM reservations r JOIN test_requests q ON q.id = r.request_id
                WHERE r.external_system = 'ASTRA_DEMO'
                ORDER BY r.external_ref, r.id DESC""");
    }

    private static void assertInWindow(Object startAt) {
        java.time.Instant start = ((java.sql.Timestamp) startAt).toInstant();
        java.time.Instant end = LocalDate.now(KST).plusDays(14).atStartOfDay(KST).toInstant();
        assertThat(start).isAfter(java.time.Instant.now()).isBefore(end);
    }
}
