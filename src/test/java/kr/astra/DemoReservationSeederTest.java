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
 * 시연 예약: lab1 조직 장비에 오늘부터 +1~+21일 예약 9건 (CONFIRMED 7·PROPOSED 2, 장비 2~3대, 결과 등록 1건).
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

    @Autowired
    kr.astra.reservation.service.ReservationService reservationService;

    @Test
    void 오늘부터_21일_안에_예약을_만들고_다시_돌려도_늘지_않으며_지난_예약은_옮긴다() {
        assertThat(seeder.refresh()).isEqualTo(9);

        List<Map<String, Object>> rows = demoReservations();
        assertThat(rows).hasSize(9);
        assertThat(rows).extracting(r -> r.get("status")).containsOnly("CONFIRMED", "PROPOSED");
        assertThat(rows.stream().filter(r -> "CONFIRMED".equals(r.get("status"))).count()).isEqualTo(7);
        assertThat(rows.stream().map(r -> r.get("equipment_id")).distinct().count()).isBetween(2L, 3L);
        // 여러 날에 걸친 확정 일정과, 같은 날 확정 일정 2건
        assertThat(rows.stream().filter(r -> "CONFIRMED".equals(r.get("status")))
                .filter(r -> ((Number) r.get("days")).intValue() >= 1).count()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT max(c) FROM (SELECT count(*) c FROM reservations
                WHERE external_system = 'ASTRA_DEMO' AND status = 'CONFIRMED'
                GROUP BY (start_at AT TIME ZONE 'Asia/Seoul')::date) d""", Long.class)).isGreaterThanOrEqualTo(2);
        // 결과까지 등록된 확정 의뢰 1건 ("시험 완료" 시연용)
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reservations r JOIN test_requests q ON q.id = r.request_id
                JOIN test_results t ON t.request_id = q.id
                WHERE r.external_system = 'ASTRA_DEMO' AND q.status = 'CONFIRMED'""", Long.class)).isEqualTo(1);
        Long managerOrg = jdbc.queryForObject("SELECT organization_id FROM users WHERE email = 'lab1@astra.kr'",
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
        assertThat(seeder.refresh()).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_requests", Long.class)).isEqualTo(requests);

        // 제목은 장비 시험유형에 맞음 (진동시험 제목은 진동시험 장비에만)
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reservations r JOIN test_requests q ON q.id = r.request_id
                WHERE r.external_system = 'ASTRA_DEMO' AND q.title LIKE '%진동%'
                  AND NOT EXISTS (SELECT 1 FROM equipment_test_types t
                                  WHERE t.equipment_id = r.equipment_id AND t.test_type_code = 'VIBRATION')""",
                Long.class)).isZero();
        // 예전 버전이 남긴 맞지 않는 제목·목적은 다음 실행 때 장비에 맞게 바뀜
        jdbc.update("""
                UPDATE test_requests SET title = '옛 제목' WHERE id IN (
                  SELECT request_id FROM reservations WHERE external_system = 'ASTRA_DEMO')""");
        jdbc.update("""
                UPDATE test_request_revisions SET content = jsonb_set(content, '{test,purpose}', '"옛 목적"')
                WHERE request_id IN (SELECT request_id FROM reservations WHERE external_system = 'ASTRA_DEMO')""");
        assertThat(seeder.refresh()).isEqualTo(9);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM test_requests q JOIN reservations r ON r.request_id = q.id
                WHERE r.external_system = 'ASTRA_DEMO' AND q.title = '옛 제목'""", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM test_request_revisions v JOIN reservations r ON r.request_id = v.request_id
                WHERE r.external_system = 'ASTRA_DEMO' AND v.content #>> '{test,purpose}' = '옛 목적'""", Long.class))
                .isZero();

        // 날짜가 지나면 오늘 기준으로 옮김 (시연 예약끼리 겹치지 않고 전부 옮겨짐)
        jdbc.update("""
                UPDATE reservations SET start_at = start_at - interval '30 days', end_at = end_at - interval '30 days'
                WHERE external_system = 'ASTRA_DEMO'""");
        assertThat(seeder.refresh()).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_requests", Long.class)).isEqualTo(requests);
        assertThat(demoReservations()).hasSize(9)
                .allSatisfy(r -> assertInWindow(r.get("start_at")));

        // 하루씩만 밀린 경우도 (매일 0시 10분 실행) 겹침 없이 옮김
        jdbc.update("""
                UPDATE reservations SET start_at = start_at - interval '1 day', end_at = end_at - interval '1 day'
                WHERE external_system = 'ASTRA_DEMO'""");
        assertThat(seeder.refresh()).isEqualTo(9);

        // 기관·기업 대시보드의 다가오는 확정 일정에 보임
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reservations r JOIN test_requests q ON q.id = r.request_id
                WHERE q.institution_id = ? AND r.status = 'CONFIRMED' AND r.end_at > now()""", Long.class, managerOrg))
                .isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reservations r JOIN test_requests q ON q.id = r.request_id
                JOIN users u ON u.id = q.requester_id
                WHERE u.email = 'company@astra.kr' AND r.status = 'CONFIRMED' AND r.end_at > now()""", Long.class))
                .isEqualTo(7);

        // 시연 데이터가 만든 알림은 모두 읽음 (헤더 알림 숫자가 쌓이지 않게)
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notifications n JOIN reservations r ON r.request_id = n.related_request_id
                WHERE r.external_system = 'ASTRA_DEMO' AND n.read_at IS NULL""", Long.class)).isZero();

        // 대시보드 다가오는 일정: 기업·기관 모두 7건
        Long companyId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'company@astra.kr'", Long.class);
        assertThat(reservationService.upcomingForRequester(companyId, 30)).hasSize(7);
        assertThat(reservationService.upcomingForInstitution(managerOrg, 30)).hasSize(7);

        // 시험 완료된 칸은 다가오는 일정에서 빠지고, 다음 실행 때 새 의뢰로 다시 만듦
        jdbc.update("""
                UPDATE test_requests SET status = 'COMPLETED' WHERE id = (
                  SELECT r.request_id FROM reservations r JOIN test_results t ON t.request_id = r.request_id
                  WHERE r.external_system = 'ASTRA_DEMO' ORDER BY r.id DESC LIMIT 1)""");
        assertThat(reservationService.upcomingForRequester(companyId, 30)).hasSize(6);
        assertThat(seeder.refresh()).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_requests", Long.class)).isEqualTo(requests + 1);
    }

    private List<Map<String, Object>> demoReservations() {
        return jdbc.queryForList("""
                SELECT DISTINCT ON (r.external_ref) r.status, r.equipment_id, r.start_at, q.institution_id,
                       q.status AS request_status, q.title,
                       (r.end_at AT TIME ZONE 'Asia/Seoul')::date - (r.start_at AT TIME ZONE 'Asia/Seoul')::date AS days
                FROM reservations r JOIN test_requests q ON q.id = r.request_id
                WHERE r.external_system = 'ASTRA_DEMO'
                ORDER BY r.external_ref, r.id DESC""");
    }

    private static void assertInWindow(Object startAt) {
        java.time.Instant start = ((java.sql.Timestamp) startAt).toInstant();
        java.time.Instant end = LocalDate.now(KST).plusDays(22).atStartOfDay(KST).toInstant();
        assertThat(start).isAfter(java.time.Instant.now()).isBefore(end);
    }
}
