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
        company = token("company@astra.kr", UserRole.COMPANY_USER);
        manager1 = token("lab1@astra.kr", UserRole.INSTITUTION_MANAGER);
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

    // ------------------------------------------------------------ 의뢰서 필수 항목 (회의 결정: 실사용자·회계 담당자)

    @Test
    void 새_의뢰서는_실사용자를_신청자로_채우고_회계_담당자는_비워_둔다() throws Exception {
        Res created = api.post("/api/v1/test-requests", "{ \"equipmentId\": 48 }", company);
        JsonNode content = created.data().path("revision").path("content");
        assertThat(content.path("actualUser").path("name").asString()).isNotBlank();
        assertThat(content.path("actualUser").path("email").asString()).isEqualTo("company@astra.kr");
        assertThat(content.path("accounting").isObject()).isTrue();
        assertThat(content.path("accounting").size()).isZero();
    }

    @Test
    void 회계_담당자가_없으면_제출할_수_없고_채우면_제출된다() throws Exception {
        long id = api.post("/api/v1/test-requests", "{ \"equipmentId\": 48 }", company).data().path("id").asLong();
        Res missing = api.post("/api/v1/test-requests/" + id + "/submit", null, company);
        assertThat(missing.status()).as(missing.body().toString()).isEqualTo(400);
        assertThat(missing.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(missing.details().path("missingItems").toString())
                .contains("accounting.name", "accounting.contact", "schedule.desiredStartDate", "schedule.desiredEndDate")
                .doesNotContain("actualUser");

        Res submitted = api.submitRequest(id, company);
        assertThat(submitted.status()).as(submitted.body().toString()).isEqualTo(200);
        assertThat(submitted.data().path("status").asString()).isEqualTo("SUBMITTED");
    }

    // ------------------------------------------------------------ 추가 정보 요청 (회의 결정: 검토 중에 4개 항목을 한 번 더 받음)

    @Test
    void 추가_정보_요청은_기본_4개_항목으로_보내지고_채워야_다시_제출된다() throws Exception {
        long id = toUnderReview();
        Res req = api.post("/api/v1/test-requests/" + id + "/supplements", "{ \"kind\": \"ADDITIONAL_INFO\" }", manager1);
        assertThat(req.status()).as(req.body().toString()).isEqualTo(201);
        JsonNode supp = req.data().path("supplement");
        assertThat(supp.path("kind").asString()).isEqualTo("ADDITIONAL_INFO");
        assertThat(supp.path("message").asString()).isNotBlank();
        assertThat(supp.path("items").toString())
                .contains("responsible.name", "report.type", "sample.disposal", "billing.method");
        assertThat(req.data().path("request").path("status").asString()).isEqualTo("SUPPLEMENT_REQUESTED");
        String title = jdbc.queryForObject("""
                SELECT n.title FROM notifications n JOIN users u ON u.id = n.user_id
                WHERE u.email = 'company@astra.kr' AND n.type = 'SUPPLEMENT_REQUESTED' AND n.related_request_id = ?
                """, String.class, id);
        assertThat(title).contains("추가 정보");

        long suppId = supp.path("id").asLong();
        Res missing = api.post("/api/v1/supplements/" + suppId + "/respond", "{ \"message\": \"아직\" }", company);
        assertThat(missing.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(missing.details().path("missingItems").size()).isEqualTo(4);

        JsonNode content = api.get("/api/v1/test-requests/" + id, company).data().path("revision").path("content");
        var filled = (tools.jackson.databind.node.ObjectNode) content.deepCopy();
        filled.putObject("responsible").put("name", "박책임");
        filled.putObject("report").put("type", "공인성적서(KOLAS)");
        ((tools.jackson.databind.node.ObjectNode) filled.path("sample")).put("disposal", "시험 후 반환");
        filled.putObject("billing").put("method", "세금계산서");
        Res responded = api.post("/api/v1/supplements/" + suppId + "/respond",
                "{ \"message\": \"입력했습니다\", \"content\": " + filled + " }", company);
        assertThat(responded.status()).as(responded.body().toString()).isEqualTo(200);
        assertThat(responded.data().path("status").asString()).isEqualTo("RESUBMITTED");
    }

    @Test
    void 일반_보완_요청은_메시지가_꼭_있어야_한다() throws Exception {
        long id = toUnderReview();
        Res res = api.post("/api/v1/test-requests/" + id + "/supplements", "{ }", manager1);
        assertThat(res.code()).isEqualTo("VALIDATION_FAILED");
    }

    // ------------------------------------------------------------ 예상 이용료 (회의 결정: 기관이 직접 입력)

    @Test
    void 기관이_검토_중에_예상_이용료를_적으면_의뢰자도_상세에서_본다() throws Exception {
        long id = toUnderReview();
        int version = api.get("/api/v1/test-requests/" + id, manager1).data().path("version").asInt();
        Res saved = api.send("PUT", "/api/v1/test-requests/" + id + "/estimated-fee",
                "{ \"version\": " + version + ", \"amount\": 1250000, \"note\": \"진동시험 2일, 고정구 제작비 별도\" }", manager1);
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
        JsonNode fee = api.get("/api/v1/test-requests/" + id, company).data().path("estimatedFee");
        assertThat(fee.path("amount").asLong()).isEqualTo(1250000L);
        assertThat(fee.path("note").asString()).contains("고정구");
        assertThat(fee.path("updatedAt").isNull()).isFalse();
    }

    @Test
    void 예상_이용료는_장비_보유_조직만_검토부터_일정_조율까지_적을_수_있다() throws Exception {
        long id = toUnderReview();
        assertThat(api.send("PUT", "/api/v1/test-requests/" + id + "/estimated-fee", "{ \"amount\": 1000 }", company)
                .status()).isEqualTo(403);
        assertThat(api.send("PUT", "/api/v1/test-requests/" + id + "/estimated-fee", "{ \"amount\": -1 }", manager1)
                .code()).isEqualTo("VALIDATION_FAILED");

        long draft = api.post("/api/v1/test-requests", "{ \"equipmentId\": 48 }", company).data().path("id").asLong();
        assertThat(api.send("PUT", "/api/v1/test-requests/" + draft + "/estimated-fee", "{ \"amount\": 1000 }", manager1)
                .status()).isIn(403, 409);
    }

    // ------------------------------------------------------------ 시험 결과·성적서 (회의 결정: 기관이 대신 시험하고 결과를 보냄)

    @Test
    void 결과를_등록해야_완료되고_의뢰자는_결과와_알림을_받는다() throws Exception {
        long id = toScheduling();
        long resv = propose(id, start(20));
        assertThat(api.post("/api/v1/reservations/" + resv + "/confirm", null, manager1).status()).isEqualTo(200);

        Res early = api.post("/api/v1/test-requests/" + id + "/complete", null, manager1);
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.code()).isEqualTo("REQUEST_INVALID_STATUS");

        String body = """
                { "summary": "20~2000Hz 정현파 진동시험 3축 완료. 시료 외관 이상 없음",
                  "reportNo": "KOLAS-2026-1234", "reportUrl": "https://files.example.com/report-1234.pdf" }
                """;
        assertThat(api.send("PUT", "/api/v1/test-requests/" + id + "/result", body, company).status()).isEqualTo(403);
        Res saved = api.send("PUT", "/api/v1/test-requests/" + id + "/result", body, manager1);
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);

        JsonNode result = api.get("/api/v1/test-requests/" + id, company).data().path("result");
        assertThat(result.path("summary").asString()).contains("3축 완료");
        assertThat(result.path("reportNo").asString()).isEqualTo("KOLAS-2026-1234");
        assertThat(result.path("reportUrl").asString()).endsWith("report-1234.pdf");
        assertThat(result.path("registeredBy").path("id").asLong()).isPositive();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notifications n JOIN users u ON u.id = n.user_id
                WHERE u.email = 'company@astra.kr' AND n.type = 'RESULT_REGISTERED' AND n.related_request_id = ?
                """, Long.class, id)).isEqualTo(1);

        Res completed = api.post("/api/v1/test-requests/" + id + "/complete", null, manager1);
        assertThat(completed.status()).as(completed.body().toString()).isEqualTo(200);
        assertThat(completed.data().path("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    void 결과는_일정_확정_뒤에만_등록하고_링크는_http_주소만_받는다() throws Exception {
        long id = toScheduling();
        Res tooEarly = api.send("PUT", "/api/v1/test-requests/" + id + "/result", "{ \"summary\": \"완료\" }", manager1);
        assertThat(tooEarly.code()).isEqualTo("REQUEST_INVALID_STATUS");

        long resv = propose(id, start(21));
        assertThat(api.post("/api/v1/reservations/" + resv + "/confirm", null, manager1).status()).isEqualTo(200);
        Res badUrl = api.send("PUT", "/api/v1/test-requests/" + id + "/result",
                "{ \"summary\": \"완료\", \"reportUrl\": \"javascript:alert(1)\" }", manager1);
        assertThat(badUrl.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(api.send("PUT", "/api/v1/test-requests/" + id + "/result", "{ \"summary\": \" \" }", manager1)
                .code()).isEqualTo("VALIDATION_FAILED");
    }

    // ------------------------------------------------------------ 첨부파일·성적서 파일 (서버 디스크 저장)

    @Test
    void 성적서_파일을_올리면_의뢰자가_목록에서_보고_내려받는다() throws Exception {
        long id = toConfirmed(22);
        byte[] pdf = "%PDF-1.4 성적서".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Res up = api.upload("/api/v1/test-requests/" + id + "/attachments", "성적서 KOLAS-1234.pdf", "application/pdf", pdf,
                manager1, "category", "RESULT_REPORT");
        assertThat(up.status()).as(up.body().toString()).isEqualTo(201);
        long fileId = up.data().path("id").asLong();
        assertThat(up.data().path("category").asString()).isEqualTo("RESULT_REPORT");
        assertThat(up.data().path("fileName").asString()).isEqualTo("성적서 KOLAS-1234.pdf");
        assertThat(up.data().path("sizeBytes").asLong()).isEqualTo(pdf.length);

        Res list = api.get("/api/v1/test-requests/" + id + "/attachments", company);
        assertThat(list.data().toString()).contains("\"id\":" + fileId);
        var file = api.download("/api/v1/attachments/" + fileId + "/download", company);
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).isEqualTo(pdf);
        assertThat(file.headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment");
        assertThat(file.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notifications n JOIN users u ON u.id = n.user_id
                WHERE u.email = 'company@astra.kr' AND n.type = 'RESULT_REGISTERED' AND n.related_request_id = ?
                """, Long.class, id)).isEqualTo(1);

        // 다른 조직은 목록도 파일도 볼 수 없음
        String manager2 = token("lab2@astra.kr", UserRole.INSTITUTION_MANAGER);
        assertThat(api.get("/api/v1/test-requests/" + id + "/attachments", manager2).status()).isEqualTo(403);
        assertThat(api.download("/api/v1/attachments/" + fileId + "/download", manager2).statusCode()).isEqualTo(403);
    }

    @Test
    void 성적서는_장비_보유_조직이_일정_확정_뒤에만_올린다() throws Exception {
        long id = toScheduling();
        byte[] pdf = "%PDF-1.4".getBytes();
        Res early = api.upload("/api/v1/test-requests/" + id + "/attachments", "r.pdf", "application/pdf", pdf, manager1,
                "category", "RESULT_REPORT");
        assertThat(early.code()).isEqualTo("REQUEST_INVALID_STATUS");
        Res byRequester = api.upload("/api/v1/test-requests/" + id + "/attachments", "r.pdf", "application/pdf", pdf,
                company, "category", "RESULT_REPORT");
        assertThat(byRequester.status()).isEqualTo(403);
        // 일반 첨부는 양쪽 모두 가능
        Res general = api.upload("/api/v1/test-requests/" + id + "/attachments", "시료 사진.png", "image/png",
                new byte[] {(byte) 0x89, 'P', 'N', 'G'}, company);
        assertThat(general.status()).as(general.body().toString()).isEqualTo(201);
        assertThat(general.data().path("category").asString()).isEqualTo("GENERAL");
    }

    @Test
    void 허용되지_않는_파일과_너무_큰_파일은_400() throws Exception {
        long id = toScheduling();
        Res exe = api.upload("/api/v1/test-requests/" + id + "/attachments", "virus.exe", "application/octet-stream",
                new byte[] {1, 2, 3}, company);
        assertThat(exe.status()).isEqualTo(400);
        assertThat(exe.code()).isEqualTo("VALIDATION_FAILED");
        Res big = api.upload("/api/v1/test-requests/" + id + "/attachments", "big.pdf", "application/pdf",
                new byte[1_500_000], company);
        assertThat(big.status()).isEqualTo(400);
        assertThat(big.code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void 올린_사람만_첨부를_지우고_지운_파일은_404() throws Exception {
        long id = toScheduling();
        long fileId = api.upload("/api/v1/test-requests/" + id + "/attachments", "메모.txt", "text/plain",
                "메모".getBytes(java.nio.charset.StandardCharsets.UTF_8), company).data().path("id").asLong();
        assertThat(api.send("DELETE", "/api/v1/attachments/" + fileId, null, manager1).status()).isEqualTo(403);
        assertThat(api.send("DELETE", "/api/v1/attachments/" + fileId, null, company).status()).isEqualTo(200);
        assertThat(api.download("/api/v1/attachments/" + fileId + "/download", company).statusCode()).isEqualTo(404);
    }

    private long toConfirmed(int days) throws Exception {
        long id = toScheduling();
        long resv = propose(id, start(days));
        assertThat(api.post("/api/v1/reservations/" + resv + "/confirm", null, manager1).status()).isEqualTo(200);
        return id;
    }

    // ------------------------------------------------------------ 도우미

    private long toUnderReview() throws Exception {
        long id = api.post("/api/v1/test-requests", "{ \"equipmentId\": 48 }", company).data().path("id").asLong();
        assertThat(api.submitRequest(id, company).status()).isEqualTo(200);
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
