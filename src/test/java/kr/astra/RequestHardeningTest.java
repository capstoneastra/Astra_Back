package kr.astra;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import kr.astra.TestApi.Res;
import kr.astra.TestMailConfiguration.CapturingEmailSender;
import kr.astra.global.security.JwtProvider;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 의뢰·보완·예약·시뮬레이션 안정화 회귀 테스트. RequestWorkflowTest 와 같은 컨텍스트(DB)를 씁니다.
 * 장비 48 일정은 RequestWorkflowTest 가 쓰는 구간(+30일 이후)과 겹치지 않게 +2~+9일을 씁니다.
 */
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class, RequestWorkflowTest.Isolated.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestHardeningTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    CapturingEmailSender mail;

    private TestApi api;
    private String company;
    private String manager1;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, mail);
        company = token("company@astra.demo", UserRole.COMPANY_USER);
        manager1 = token("manager1@astra.demo", UserRole.INSTITUTION_MANAGER);
    }

    // ------------------------------------------------------------ 보완 항목

    @Test
    void 보완_항목에_null_원소가_있으면_400() throws Exception {
        long id = toUnderReview();
        Res res = api.post("/api/v1/test-requests/" + id + "/supplements",
                "{ \"message\": \"확인 부탁\", \"items\": [ null ] }", manager1);
        assertThat(res.status()).as(res.body().toString()).isEqualTo(400);
        assertThat(res.code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void 저장된_보완_항목에_null_이_섞여_있어도_보완_제출이_된다() throws Exception {
        long id = toUnderReview();
        Res supp = api.post("/api/v1/test-requests/" + id + "/supplements", "{ \"message\": \"확인 부탁\" }", manager1);
        assertThat(supp.status()).as(supp.body().toString()).isEqualTo(201);
        long suppId = supp.data().path("supplement").path("id").asLong();
        jdbc.update("UPDATE supplement_requests SET items = '[null, {\"fieldKey\": \"notes\", \"required\": false}]'"
                + "::jsonb WHERE id = ?", suppId);

        Res list = api.get("/api/v1/test-requests/" + id + "/supplements", company);
        assertThat(list.status()).as(list.body().toString()).isEqualTo(200);
        assertThat(list.data().get(0).path("items").size()).isEqualTo(1);

        Res responded = api.post("/api/v1/supplements/" + suppId + "/respond", "{ \"message\": \"보완했습니다.\" }",
                company);
        assertThat(responded.status()).as(responded.body().toString()).isEqualTo(200);
        assertThat(responded.data().path("status").asString()).isEqualTo("RESUBMITTED");
    }

    // ------------------------------------------------------------ 예약 확정

    @Test
    void 시작_시각이_지난_제안은_확정할_수_없다() throws Exception {
        long id = toScheduling();
        long resv = propose(id, start(2));
        jdbc.update("UPDATE reservations SET start_at = now() - interval '2 hour', end_at = now() - interval '1 hour'"
                + " WHERE id = ?", resv);
        Res res = api.post("/api/v1/reservations/" + resv + "/confirm", null, manager1);
        assertThat(res.status()).as(res.body().toString()).isEqualTo(400);
        assertThat(res.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(res.details().path("field").asString()).isEqualTo("startAt");
        assertThat(status(resv)).isEqualTo("PROPOSED");
    }

    @Test
    void 확정하면_예약_version_이_올라가고_대시보드에_의뢰번호가_보인다() throws Exception {
        long id = toScheduling();
        long resv = propose(id, start(3));
        long before = jdbc.queryForObject("SELECT version FROM reservations WHERE id = ?", Long.class, resv);
        Res confirmed = api.post("/api/v1/reservations/" + resv + "/confirm", null, manager1);
        assertThat(confirmed.status()).as(confirmed.body().toString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT version FROM reservations WHERE id = ?", Long.class, resv))
                .isGreaterThan(before);

        String requestNo = jdbc.queryForObject("SELECT request_no FROM test_requests WHERE id = ?", String.class, id);
        JsonNode upcoming = api.get("/api/v1/dashboard/summary?view=PROVIDER", manager1).data()
                .path("upcomingReservations");
        JsonNode mine = null;
        for (JsonNode item : upcoming) {
            if (item.path("reservationId").asLong() == resv) {
                mine = item;
            }
        }
        assertThat(mine).as(upcoming.toString()).isNotNull();
        assertThat(mine.path("requestId").asLong()).isEqualTo(id);
        assertThat(mine.path("requestNo").asString()).isEqualTo(requestNo);
        assertThat(mine.path("equipmentName").asString()).isNotBlank();
    }

    /**
     * 확정 요청이 의뢰 잠금을 기다리는 동안 다른 트랜잭션이 그 제안을 취소하면,
     * 잠금을 얻은 뒤 다시 읽은 상태(CANCELED)로 판단해 409 를 줘야 한다 (예전 사본으로 CONFIRMED 덮어쓰기 금지).
     */
    @Test
    void 잠금_대기_중_취소된_제안은_확정되지_않는다() throws Exception {
        long id = toScheduling();
        long resv = propose(id, start(4));

        CompletableFuture<Res> confirm;
        try (Connection tx = dataSource.getConnection()) {
            tx.setAutoCommit(false);
            try (PreparedStatement lock = tx.prepareStatement("SELECT id FROM test_requests WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, id);
                lock.executeQuery();
            }
            confirm = CompletableFuture.supplyAsync(() -> {
                try {
                    return api.post("/api/v1/reservations/" + resv + "/confirm", null, manager1);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            awaitLockWaiter();
            try (PreparedStatement cancel = tx.prepareStatement(
                    "UPDATE reservations SET status = 'CANCELED' WHERE id = ?")) {
                cancel.setLong(1, resv);
                cancel.executeUpdate();
            }
            tx.commit();
        }
        Res res = confirm.get(30, TimeUnit.SECONDS);
        assertThat(res.status()).as(res.body().toString()).isEqualTo(409);
        assertThat(status(resv)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT status FROM test_requests WHERE id = ?", String.class, id))
                .isEqualTo("SCHEDULING");
    }

    // ------------------------------------------------------------ 비활성 장비 표시

    @Test
    void 의뢰_후_비활성화된_장비는_목록과_상세에서_비활성_장비로_보인다() throws Exception {
        Res equipment = api.post("/api/v1/equipment", """
                { "name": "기업 보유 시험기 %s", "model": "H-1", "equipmentType": "TEST",
                  "usage": { "accessScope": "EXTERNAL_ALLOWED", "availability": "AVAILABLE" },
                  "testTypes": [ { "code": "VIBRATION" } ] }""".formatted(java.util.UUID.randomUUID()), company);
        assertThat(equipment.status()).as(equipment.body().toString()).isEqualTo(201);
        long equipmentId = equipment.data().path("id").asLong();
        Res created = api.post("/api/v1/test-requests", "{ \"equipmentId\": " + equipmentId + " }", manager1);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        assertThat(created.data().path("equipment").path("name").asString()).startsWith("기업 보유 시험기");
        long id = created.data().path("id").asLong();

        assertThat(api.send("DELETE", "/api/v1/equipment/" + equipmentId, null, company).status()).isLessThan(300);

        JsonNode detail = api.get("/api/v1/test-requests/" + id, manager1).data();
        assertThat(detail.path("equipment").path("id").asLong()).isEqualTo(equipmentId);
        assertThat(detail.path("equipment").path("name").asString()).isEqualTo("(비활성 장비)");
        JsonNode list = api.get("/api/v1/test-requests?box=SENT&size=100", manager1).data().path("content");
        boolean found = false;
        for (JsonNode row : list) {
            if (row.path("id").asLong() == id) {
                found = true;
                assertThat(row.path("equipment").path("name").asString()).isEqualTo("(비활성 장비)");
            } else {
                assertThat(row.path("equipment").path("name").asString()).isNotBlank();
            }
        }
        assertThat(found).isTrue();
    }

    // ------------------------------------------------------------ 시뮬레이션

    @Test
    void 계산하지_않는_모델도_입력값은_짧은_스칼라만_받는다() throws Exception {
        Res nested = api.post("/api/v1/simulation/runs",
                "{ \"presetId\": \"SIM-0020\", \"inputParameters\": { \"sampleName\": { \"a\": [1, 2, 3] } } }",
                company);
        assertThat(nested.status()).as(nested.body().toString()).isEqualTo(400);
        assertThat(nested.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(nested.details().path("field").asString()).isEqualTo("inputParameters.sampleName");

        Res huge = api.post("/api/v1/simulation/runs",
                "{ \"presetId\": \"SIM-0020\", \"inputParameters\": { \"sampleName\": \"" + "가".repeat(201) + "\" } }",
                company);
        assertThat(huge.code()).isEqualTo("VALIDATION_FAILED");

        Res ok = api.post("/api/v1/simulation/runs",
                "{ \"presetId\": \"SIM-0020\", \"inputParameters\": { \"sampleName\": \"알루미늄 시편\" } }", company);
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(201);
        assertThat(ok.data().path("status").asString()).isEqualTo("NOT_EXECUTED");
    }

    @Test
    void 계산_결과가_넘치는_입력은_400() throws Exception {
        Res res = api.post("/api/v1/simulation/runs", """
                { "presetId": "SIM-0018",
                  "inputParameters": { "specimenStiffnessNPerMm": 1e300, "maxDisplacementMm": 1e300 } }
                """, company);
        assertThat(res.status()).as(res.body().toString()).isEqualTo(400);
        assertThat(res.code()).isEqualTo("VALIDATION_FAILED");
    }

    // ------------------------------------------------------------ 도우미

    private long toUnderReview() throws Exception {
        long id = api.post("/api/v1/test-requests", "{ \"equipmentId\": 48 }", company).data().path("id").asLong();
        assertThat(api.post("/api/v1/test-requests/" + id + "/submit", null, company).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/test-requests/" + id + "/review/start", null, manager1).status()).isEqualTo(200);
        return id;
    }

    private long toScheduling() throws Exception {
        long id = toUnderReview();
        assertThat(api.post("/api/v1/test-requests/" + id + "/accept", null, manager1).data().path("status").asString())
                .isEqualTo("SCHEDULING");
        return id;
    }

    private long propose(long requestId, OffsetDateTime start) throws Exception {
        Res proposed = api.post("/api/v1/test-requests/" + requestId + "/reservations",
                "{ \"startAt\": \"%s\", \"endAt\": \"%s\" }".formatted(start, start.plusHours(2)), company);
        assertThat(proposed.status()).as(proposed.body().toString()).isEqualTo(201);
        return proposed.data().path("reservation").path("id").asLong();
    }

    private static OffsetDateTime start(int days) {
        return OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(days).truncatedTo(ChronoUnit.HOURS).withHour(10);
    }

    private String status(long reservationId) {
        return jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class, reservationId);
    }

    /** 다른 세션이 행 잠금을 기다릴 때까지 대기 (최대 10초) */
    private void awaitLockWaiter() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("확정 요청이 의뢰 잠금을 기다리지 않았습니다.");
    }

    private String token(String email, UserRole role) {
        var row = jdbc.queryForMap("SELECT id, organization_id FROM users WHERE email = ?", email);
        return jwtProvider.createAccessToken(((Number) row.get("id")).longValue(), role,
                row.get("organization_id") == null ? null : ((Number) row.get("organization_id")).longValue());
    }
}
