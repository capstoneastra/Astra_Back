package kr.astra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import kr.astra.TestApi.Res;
import kr.astra.TestMailConfiguration.CapturingEmailSender;
import kr.astra.global.security.JwtProvider;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * BE-B 의뢰 워크플로우 (api-spec §5·§6·§7·§9·§10, 상태 전이 §11) — 시연 시나리오 11~16단계.
 * 의뢰를 만들고 상태를 바꾸므로 의뢰 0건을 가정하는 DashboardSearchApiTest 와 다른 DB(테스트 컨텍스트)를 씁니다.
 * 시연 계정: company(가상 A기업), manager1 = 미리내우주환경시험센터(장비 48 진동시험기 보유), manager2 = 다른 기관.
 */
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class, RequestWorkflowTest.Isolated.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestWorkflowTest {

    /** 이 테스트만의 컨텍스트(=DB)를 쓰기 위한 표시 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Isolated {
    }

    static final long EQUIPMENT = 48;

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    CapturingEmailSender mail;

    private TestApi api;
    private String company;
    private String manager1;
    private String manager2;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, mail);
        company = token("company@astra.kr", UserRole.COMPANY_USER);
        manager1 = token("lab1@astra.kr", UserRole.INSTITUTION_MANAGER);
        manager2 = token("lab2@astra.kr", UserRole.INSTITUTION_MANAGER);
    }

    @Test
    void 제출_보완_재제출_검토통과_예약확정까지_시연_흐름이_이어진다() throws Exception {
        // 11. 의뢰 생성 → DRAFT, 접수 기관은 장비 소속 기관
        Res created = api.post("/api/v1/test-requests", "{ \"equipmentId\": 48, \"title\": \"위성 전장품 진동시험\" }",
                company);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        long id = created.data().path("id").asLong();
        assertThat(created.data().path("status").asString()).isEqualTo("DRAFT");
        assertThat(created.data().path("requestNo").asString()).matches("REQ-\\d{8}-\\d{4}");
        assertThat(created.data().path("institution").path("id").asLong()).isEqualTo(2);
        assertThat(created.data().path("revision").path("content").path("applicant").path("companyName").asString())
                .isEqualTo("가상 A기업");
        assertThat(actions(created.data())).contains("SUBMIT", "CANCEL");

        // 제출 전에는 기관 접수함에 보이지 않음
        assertThat(api.get("/api/v1/test-requests/" + id, manager1).status()).isEqualTo(403);

        // 작업본 저장 → version 증가
        int v0 = created.data().path("version").asInt();
        Res saved = api.send("PUT", "/api/v1/test-requests/" + id + "/draft", draftBody(v0, false), company);
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
        assertThat(saved.data().path("version").asInt()).isGreaterThan(v0);
        assertThat(saved.data().path("revisionNo").asInt()).isEqualTo(1);
        // 옛 version 으로 저장하면 409
        assertThat(api.send("PUT", "/api/v1/test-requests/" + id + "/draft", draftBody(v0, false), company).code())
                .isEqualTo("VERSION_CONFLICT");

        Res submitted = api.submitRequest(id, company);
        assertThat(submitted.status()).as(submitted.body().toString()).isEqualTo(200);
        assertThat(submitted.data().path("status").asString()).isEqualTo("SUBMITTED");
        assertThat(unread("lab1@astra.kr", "REQUEST_SUBMITTED", id)).isEqualTo(1);
        // 다시 제출하면 상태 전이 불가
        assertThat(api.post("/api/v1/test-requests/" + id + "/submit", null, company).code())
                .isEqualTo("REQUEST_INVALID_STATUS");

        // 12. 기관 접수함 + 대시보드 검토 대기
        assertThat(api.get("/api/v1/test-requests?size=100", manager1).data().path("content").toString())
                .contains("\"id\":" + id + ",");
        JsonNode dash = api.get("/api/v1/dashboard/summary", manager1).data();
        assertThat(dash.path("pendingReview").toString()).contains("\"requestId\":" + id + ",");
        // 다른 기관 담당자는 볼 수 없음
        assertThat(api.get("/api/v1/test-requests/" + id, manager2).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/test-requests", manager2).data().path("totalElements").asLong()).isZero();
        // 기업은 검토 시작을 못 함
        assertThat(api.post("/api/v1/test-requests/" + id + "/review/start", null, company).status()).isEqualTo(403);

        // 13. 검토 시작 → 보완 요청
        Res review = api.post("/api/v1/test-requests/" + id + "/review/start", null, manager1);
        assertThat(review.data().path("status").asString()).isEqualTo("UNDER_REVIEW");
        String supplementBody = """
                { "message": "고정구 질량과 시료 준비 완료일을 확인 부탁드립니다.",
                  "items": [ { "fieldKey": "fixture_mass", "label": "고정구 질량", "required": true },
                             { "fieldKey": "sample.readyDate", "label": "시료 준비 완료일", "required": true } ],
                  "dueDate": "%s" }
                """.formatted(OffsetDateTime.now().plusDays(3).toLocalDate());
        Res supp = api.post("/api/v1/test-requests/" + id + "/supplements", supplementBody, manager1);
        assertThat(supp.status()).as(supp.body().toString()).isEqualTo(201);
        assertThat(supp.data().path("request").path("status").asString()).isEqualTo("SUPPLEMENT_REQUESTED");
        long suppId = supp.data().path("supplement").path("id").asLong();
        // 중복 클릭 → 409 SUPPLEMENT_ALREADY_OPEN
        assertThat(api.post("/api/v1/test-requests/" + id + "/supplements", supplementBody, manager1).code())
                .isEqualTo("SUPPLEMENT_ALREADY_OPEN");
        assertThat(unread("company@astra.kr", "SUPPLEMENT_REQUESTED", id)).isEqualTo(1);
        JsonNode companyDash = api.get("/api/v1/dashboard/summary", company).data();
        assertThat(companyDash.path("dataAvailable").path("requests").asBoolean()).isTrue();
        assertThat(companyDash.path("actionRequired").get(0).path("status").asString()).isEqualTo("SUPPLEMENT_REQUESTED");
        assertThat(companyDash.path("actionRequired").get(0).path("dueDate").isNull()).isFalse();

        // 14. 보완 제출 — 필수 항목이 비어 있으면 400, 채우면 revision 2 + RESUBMITTED
        Res missing = api.post("/api/v1/supplements/" + suppId + "/respond",
                "{ \"content\": " + content(false) + " }", company);
        assertThat(missing.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(missing.details().path("missingItems").size()).isEqualTo(2);
        Res responded = api.post("/api/v1/supplements/" + suppId + "/respond", """
                { "message": "보완했습니다.", "content": %s, "changeSummary": "고정구 질량 2kg, 준비 완료일 입력" }
                """.formatted(content(true)), company);
        assertThat(responded.status()).as(responded.body().toString()).isEqualTo(200);
        assertThat(responded.data().path("status").asString()).isEqualTo("RESUBMITTED");
        assertThat(responded.data().path("revisionNo").asInt()).isEqualTo(2);

        JsonNode revisions = api.get("/api/v1/test-requests/" + id + "/revisions", manager1).data();
        assertThat(revisions.size()).isEqualTo(2);
        JsonNode diff = api.get("/api/v1/test-requests/" + id + "/revisions/2?diffWith=1", manager1).data();
        assertThat(diff.path("changes").toString()).contains("conditions.fixture_mass.value", "sample.readyDate");

        // 15. 재검토 → 통과 → 일정 제안 → 확정
        api.post("/api/v1/test-requests/" + id + "/review/start", null, manager1);
        Res accepted = api.post("/api/v1/test-requests/" + id + "/accept", null, manager1);
        assertThat(accepted.data().path("status").asString()).isEqualTo("SCHEDULING");

        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(30).truncatedTo(ChronoUnit.HOURS);
        Res proposed = api.post("/api/v1/test-requests/" + id + "/reservations",
                period(start, start.plusHours(33)), manager1);
        assertThat(proposed.status()).as(proposed.body().toString()).isEqualTo(201);
        long reservationId = proposed.data().path("reservation").path("id").asLong();
        assertThat(proposed.data().path("reservation").path("proposedBySide").asString()).isEqualTo("INSTITUTION");

        Res confirmed = api.post("/api/v1/reservations/" + reservationId + "/confirm", null, manager1);
        assertThat(confirmed.status()).as(confirmed.body().toString()).isEqualTo(200);
        assertThat(confirmed.data().path("request").path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(unread("company@astra.kr", "RESERVATION_CONFIRMED", id)).isEqualTo(1);
        assertThat(api.post("/api/v1/reservations/" + reservationId + "/confirm", null, manager1).code())
                .isEqualTo("RESERVATION_ALREADY_CONFIRMED");

        // 장비 달력에 확정 구간 표시
        JsonNode busy = api.get("/api/v1/equipment/48/reservations?from=" + start.toLocalDate() + "&to="
                + start.plusDays(2).toLocalDate(), company).data();
        assertThat(busy.size()).isEqualTo(1);

        // 같은 장비, 겹치는 시간 → 409 RESERVATION_OVERLAP (끝에 맞닿는 시간은 가능)
        long other = toScheduling();
        Res overlap = api.post("/api/v1/test-requests/" + other + "/reservations",
                period(start.plusHours(5), start.plusHours(40)), manager1);
        assertThat(overlap.code()).isEqualTo("RESERVATION_OVERLAP");
        assertThat(overlap.details().path("conflicts").size()).isEqualTo(1);
        Res touching = api.post("/api/v1/test-requests/" + other + "/reservations",
                period(start.plusHours(33), start.plusHours(36)), company);
        assertThat(touching.status()).as(touching.body().toString()).isEqualTo(201);

        // 이력: 생성 → 제출 → 검토 → 보완 → 재제출 → 검토 → 통과 → 확정
        JsonNode history = api.get("/api/v1/test-requests/" + id + "/history", company).data();
        assertThat(history.size()).isEqualTo(8);
        assertThat(history.get(history.size() - 1).path("toStatus").asString()).isEqualTo("CONFIRMED");

        // 완료
        // 시험 대행: 결과를 등록해야 완료
        assertThat(api.send("PUT", "/api/v1/test-requests/" + id + "/result", "{ \"summary\": \"시험 완료\" }", manager1)
                .status()).isEqualTo(200);
        Res completed = api.post("/api/v1/test-requests/" + id + "/complete", null, manager1);
        assertThat(completed.data().path("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    void 기관_회원도_다른_조직_장비에_의뢰할_수_있고_자기_조직_장비에는_의뢰할_수_없다() throws Exception {
        // manager1 소속 기관이 보유한 장비(48)에는 의뢰할 수 없다
        Res own = api.post("/api/v1/test-requests", "{ \"equipmentId\": 48 }", manager1);
        assertThat(own.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(own.details().path("field").asString()).isEqualTo("equipmentId");

        // 다른 기관(manager2 소속) 장비에는 의뢰자로 의뢰를 만들 수 있다
        long otherOrgId = jdbc.queryForObject(
                "SELECT organization_id FROM users WHERE email = 'lab2@astra.kr'", Long.class);
        long otherEquipment = jdbc.queryForObject(
                "SELECT min(id) FROM equipment WHERE institution_id = ? AND is_active", Long.class, otherOrgId);
        Res created = api.post("/api/v1/test-requests", "{ \"equipmentId\": " + otherEquipment + " }", manager1);
        assertThat(created.status()).isEqualTo(201);
        long id = created.data().path("id").asLong();

        JsonNode detail = api.get("/api/v1/test-requests/" + id, manager1).data();
        assertThat(detail.path("status").asString()).isEqualTo("DRAFT");
        assertThat(detail.path("institution").path("id").asLong()).isEqualTo(otherOrgId);
        assertThat(actions(detail)).contains("SUBMIT", "CANCEL");
        // 제출 전에는 장비 보유 조직에도 보이지 않는다
        assertThat(api.get("/api/v1/test-requests/" + id, manager2).code()).isEqualTo("FORBIDDEN");

        // 상담·시험조건도 기업 전용이 아니다
        assertThat(api.post("/api/v1/chat/sessions", "{ \"title\": \"기관 상담\" }", manager1).code())
                .isNotEqualTo("FORBIDDEN");
        assertThat(api.get("/api/v1/chat/sessions", manager1).status()).isEqualTo(200);

        assertThat(api.post("/api/v1/test-requests/" + id + "/cancel", "{ \"reason\": \"테스트 정리\" }", manager1)
                .data().path("status").asString()).isEqualTo("CANCELED");
    }

    @Test
    void 기관이_의뢰자이고_기업이_장비_보유_조직인_의뢰도_끝까지_진행된다() throws Exception {
        long companyUserId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'company@astra.kr'", Long.class);
        // 기업이 장비를 등록 (기업 = 장비 보유 조직)
        Res equipment = api.post("/api/v1/equipment", """
                { "name": "기업 보유 진동시험기 %s", "model": "C-200", "equipmentType": "TEST",
                  "usage": { "accessScope": "EXTERNAL_ALLOWED", "availability": "AVAILABLE" },
                  "specs": [ { "key": "max_payload", "label": "최대 시료 질량", "value": 80, "unit": "kg" } ],
                  "testTypes": [ { "code": "VIBRATION" } ] }""".formatted(java.util.UUID.randomUUID()), company);
        assertThat(equipment.status()).as(equipment.body().toString()).isEqualTo(201);
        long equipmentId = equipment.data().path("id").asLong();
        try {
            // 기관 회원(manager1)이 의뢰자로 그 장비에 의뢰 → 제출
            long id = api.post("/api/v1/test-requests", "{ \"equipmentId\": " + equipmentId + " }", manager1)
                    .data().path("id").asLong();
            assertThat(api.submitRequest(id, manager1).status()).isEqualTo(200);
            assertThat(unread("company@astra.kr", "REQUEST_SUBMITTED", id)).isEqualTo(1);

            // 보낸 의뢰 / 받은 의뢰는 box 로 나눠 본다
            assertThat(ids(api.get("/api/v1/test-requests?box=SENT&size=100", manager1))).contains(id);
            assertThat(ids(api.get("/api/v1/test-requests?box=RECEIVED&size=100", manager1))).doesNotContain(id);
            assertThat(ids(api.get("/api/v1/test-requests?box=RECEIVED&size=100", company))).contains(id);
            assertThat(ids(api.get("/api/v1/test-requests?box=SENT&size=100", company))).doesNotContain(id);
            // box 를 안 보내면 가입 유형 기본값: 기관 담당자는 받은 의뢰, 기업은 보낸 의뢰
            assertThat(ids(api.get("/api/v1/test-requests?size=100", manager1))).doesNotContain(id);
            assertThat(ids(api.get("/api/v1/test-requests?size=100", company))).doesNotContain(id);
            assertThat(api.get("/api/v1/test-requests?box=INBOX", company).status()).isEqualTo(400);

            // 상세 버튼: 의뢰자는 의뢰자 쪽, 보유 기업은 검토 쪽
            assertThat(actions(api.get("/api/v1/test-requests/" + id, manager1).data())).contains("CANCEL")
                    .doesNotContain("START_REVIEW");
            assertThat(actions(api.get("/api/v1/test-requests/" + id, company).data())).contains("START_REVIEW");
            // 의뢰자와 무관한 다른 기관은 볼 수 없고, 의뢰자는 자기 의뢰를 검토할 수 없다
            assertThat(api.get("/api/v1/test-requests/" + id, manager2).status()).isEqualTo(403);
            assertThat(api.post("/api/v1/test-requests/" + id + "/review/start", null, manager1).status()).isEqualTo(403);

            // 기업이 검토 시작(담당자 = 본인) → 통과 → 의뢰자에게 알림
            assertThat(api.post("/api/v1/test-requests/" + id + "/review/start", null, company).data().path("status")
                    .asString()).isEqualTo("UNDER_REVIEW");
            assertThat(jdbc.queryForObject("SELECT assignee_id FROM test_requests WHERE id = ?", Long.class, id))
                    .isEqualTo(companyUserId);
            assertThat(api.post("/api/v1/test-requests/" + id + "/accept", null, company).data().path("status")
                    .asString()).isEqualTo("SCHEDULING");
            assertThat(unread("lab1@astra.kr", "REQUEST_ACCEPTED", id)).isEqualTo(1);

            // 일정: 의뢰자가 제안하고 보유 기업이 확정
            OffsetDateTime start = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(120).truncatedTo(ChronoUnit.HOURS);
            Res proposed = api.post("/api/v1/test-requests/" + id + "/reservations", period(start, start.plusHours(2)),
                    manager1);
            assertThat(proposed.status()).as(proposed.body().toString()).isLessThan(300);
            long reservation = proposed.data().path("reservation").path("id").asLong();
            assertThat(proposed.data().path("reservation").path("proposedBySide").asString()).isEqualTo("COMPANY");
            assertThat(api.post("/api/v1/reservations/" + reservation + "/confirm", null, manager1).status())
                    .isEqualTo(403);
            Res confirmed = api.post("/api/v1/reservations/" + reservation + "/confirm", null, company);
            assertThat(confirmed.status()).as(confirmed.body().toString()).isEqualTo(200);
            assertThat(api.get("/api/v1/test-requests/" + id, manager1).data().path("status").asString())
                    .isEqualTo("CONFIRMED");
            assertThat(api.send("PUT", "/api/v1/test-requests/" + id + "/result", "{ \"summary\": \"시험 완료\" }",
                    company).status()).isEqualTo(200);
            assertThat(api.post("/api/v1/test-requests/" + id + "/complete", null, company).data().path("status")
                    .asString()).isEqualTo("COMPLETED");
        } finally {
            api.send("DELETE", "/api/v1/equipment/" + equipmentId, null, company);
        }
    }

    @Test
    void 대학과_기업이_시연_계정으로_서로의_장비를_빌린다() throws Exception {
        // V8 시연 계정: univ1 = 늘해랑대학교(대학), company2 = 미르항공정밀(장비 보유 기업)
        String univ1 = token("univ@astra.kr", UserRole.INSTITUTION_MANAGER);
        String company2 = token("company2@astra.kr", UserRole.COMPANY_USER);
        long univEquipment = usableEquipmentOf("univ@astra.kr");
        long companyEquipment = usableEquipmentOf("company2@astra.kr");

        // 대학 → 기업 장비
        long toCompany = submit(univ1, companyEquipment);
        assertThat(unread("company2@astra.kr", "REQUEST_SUBMITTED", toCompany)).isEqualTo(1);
        assertThat(ids(api.get("/api/v1/test-requests?box=RECEIVED&size=100", company2))).contains(toCompany);
        assertThat(api.post("/api/v1/test-requests/" + toCompany + "/review/start", null, company2).data()
                .path("status").asString()).isEqualTo("UNDER_REVIEW");
        assertThat(api.post("/api/v1/test-requests/" + toCompany + "/accept", null, company2).data()
                .path("status").asString()).isEqualTo("SCHEDULING");

        // 기업 → 대학 장비
        long toUniversity = submit(company2, univEquipment);
        assertThat(unread("univ@astra.kr", "REQUEST_SUBMITTED", toUniversity)).isEqualTo(1);
        assertThat(ids(api.get("/api/v1/test-requests?size=100", univ1))).contains(toUniversity); // 대학 기본값 = 받은 의뢰
        assertThat(api.post("/api/v1/test-requests/" + toUniversity + "/review/start", null, univ1).data()
                .path("status").asString()).isEqualTo("UNDER_REVIEW");

        // 한 계정이 보낸 의뢰와 받은 의뢰를 동시에 가짐
        assertThat(ids(api.get("/api/v1/test-requests?box=SENT&size=100", univ1))).contains(toCompany)
                .doesNotContain(toUniversity);
        assertThat(ids(api.get("/api/v1/test-requests?box=SENT&size=100", company2))).contains(toUniversity)
                .doesNotContain(toCompany);
        assertThat(api.get("/api/v1/dashboard/summary?view=REQUESTER", univ1).data().path("requestCounts")
                .path("SCHEDULING").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(api.get("/api/v1/dashboard/summary?view=PROVIDER", company2).data().path("requestCounts")
                .path("SCHEDULING").asLong()).isGreaterThanOrEqualTo(1);

        // 자기 조직 장비에는 의뢰할 수 없음
        assertThat(api.post("/api/v1/test-requests", "{ \"equipmentId\": " + univEquipment + " }", univ1).code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(api.post("/api/v1/test-requests", "{ \"equipmentId\": " + companyEquipment + " }", company2).code())
                .isEqualTo("VALIDATION_FAILED");
        // 의뢰와 관계없는 조직은 볼 수 없음
        assertThat(api.get("/api/v1/test-requests/" + toUniversity, manager2).status()).isEqualTo(403);
    }

    /** 의뢰를 만들고 제출 → 의뢰 id */
    private long submit(String requester, long equipmentId) throws Exception {
        Res created = api.post("/api/v1/test-requests", "{ \"equipmentId\": " + equipmentId + " }", requester);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        long id = created.data().path("id").asLong();
        Res submitted = api.submitRequest(id, requester);
        assertThat(submitted.status()).as(submitted.body().toString()).isEqualTo(200);
        return id;
    }

    /** 그 회원 소속 조직의 이용 가능한 활성 장비 하나 */
    private long usableEquipmentOf(String email) {
        return jdbc.queryForObject("""
                SELECT min(e.id) FROM equipment e JOIN users u ON u.organization_id = e.institution_id
                WHERE u.email = ? AND e.is_active AND e.availability NOT IN ('MAINTENANCE', 'UNAVAILABLE')
                """, Long.class, email);
    }

    private static java.util.List<Long> ids(Res page) {
        java.util.List<Long> list = new java.util.ArrayList<>();
        page.data().path("content").forEach(n -> list.add(n.path("id").asLong()));
        return list;
    }

    @Test
    void 취소하면_열린_일정_제안이_함께_취소된다() throws Exception {
        long id = toScheduling();
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(90).truncatedTo(ChronoUnit.HOURS);
        long resv = api.post("/api/v1/test-requests/" + id + "/reservations", period(start, start.plusHours(2)), company)
                .data().path("reservation").path("id").asLong();
        Res canceled = api.post("/api/v1/test-requests/" + id + "/cancel", "{ \"reason\": \"일정 변경\" }", company);
        assertThat(canceled.data().path("status").asString()).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class, resv))
                .isEqualTo("CANCELED");
    }

    @Test
    void 알림_목록과_읽음_처리() throws Exception {
        long id = toScheduling(); // company 에게 REQUEST_ACCEPTED 알림
        long before = api.get("/api/v1/notifications/unread-count", company).data().path("count").asLong();
        assertThat(before).isPositive();
        JsonNode first = api.get("/api/v1/notifications?unreadOnly=true", company).data().path("content").get(0);
        // 알림 문구에는 의뢰번호 말머리를 붙이지 않음 (어느 의뢰인지는 relatedRequestId)
        assertThat(first.path("title").asString()).isEqualTo("기술 검토를 통과했습니다. 일정을 협의해 주세요.");
        assertThat(first.path("relatedRequestId").asLong()).isEqualTo(id);
        Res read = api.send("PATCH", "/api/v1/notifications/" + first.path("id").asLong() + "/read", null, company);
        assertThat(read.data().path("isRead").asBoolean()).isTrue();
        // 남의 알림은 404
        assertThat(api.send("PATCH", "/api/v1/notifications/" + first.path("id").asLong() + "/read", null, manager2)
                .status()).isEqualTo(404);
        api.post("/api/v1/notifications/read-all", null, company);
        assertThat(api.get("/api/v1/notifications/unread-count", company).data().path("count").asLong()).isZero();
    }

    @Test
    void 진동시험기_SDOF_시뮬레이션은_고유진동수와_DEMO_경고를_준다() throws Exception {
        JsonNode presets = api.get("/api/v1/equipment/48/simulation-presets", company).data();
        assertThat(presets.get(0).path("presetId").asString()).isEqualTo("SIM-0048");
        assertThat(presets.get(0).path("isCalculated").asBoolean()).isTrue();

        Res run = api.post("/api/v1/simulation/runs", """
                { "presetId": "SIM-0048", "inputParameters": { "massKg": 15, "fixtureMassKg": 2,
                  "stiffnessNPerM": 100000, "dampingRatio": 0.05, "frequencyMinHz": 20, "frequencyMaxHz": 2000 } }
                """, company);
        assertThat(run.status()).as(run.body().toString()).isEqualTo(201);
        assertThat(run.data().path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.data().path("isMock").asBoolean()).isTrue();
        assertThat(run.data().path("warning").asString()).contains("가상 시연 모델");
        assertThat(run.data().path("output").path("summary").path("naturalFrequencyHz").asDouble()).isEqualTo(12.2);
        JsonNode at20 = run.data().path("output").path("chart").path("series").get(0);
        assertThat(at20.get(0).asDouble()).isEqualTo(20.0);
        assertThat(at20.get(1).asDouble()).isBetween(0.59, 0.61); // api-spec §9 예시 0.599

        assertThat(api.post("/api/v1/simulation/runs",
                "{ \"presetId\": \"SIM-0048\", \"inputParameters\": { \"massKg\": -1 } }", company).code())
                .isEqualTo("VALIDATION_FAILED");
        long runId = run.data().path("id").asLong();
        assertThat(api.get("/api/v1/simulation/runs/" + runId, manager2).status()).isEqualTo(403);
    }

    // ------------------------------------------------------------ 도우미

    /** 새 의뢰를 SCHEDULING 까지 진행 (기업 작성·제출 → manager1 검토·통과) */
    private long toScheduling() throws Exception {
        long id = api.post("/api/v1/test-requests", "{ \"equipmentId\": 48 }", company).data().path("id").asLong();
        assertThat(api.submitRequest(id, company).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/test-requests/" + id + "/review/start", null, manager1).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/test-requests/" + id + "/accept", null, manager1).data().path("status").asString())
                .isEqualTo("SCHEDULING");
        return id;
    }

    private static String draftBody(int version, boolean filled) {
        return "{ \"version\": %d, \"title\": \"위성 전장품 진동시험\", \"content\": %s }".formatted(version,
                content(filled));
    }

    /** api-spec §5 의뢰서 content. filled=false 면 고정구 질량·시료 준비일이 비어 있음 */
    private static String content(boolean filled) {
        return """
                { "applicant": { "companyName": "가상 A기업", "contactName": "홍길동" },
                  "actualUser": { "name": "홍길동", "email": "company@astra.kr" },
                  "accounting": { "name": "김회계", "phone": "02-000-0000" },
                  "test": { "testTypeCode": "VIBRATION", "purpose": "발사 환경 진동 내구성 확인" },
                  "sample": { "name": "위성 탑재 전장품", "quantity": 1 %s },
                  "conditions": [ { "key": "sample_mass", "label": "시료 질량", "value": 15, "unit": "kg", "status": "CONFIRMED" },
                                  { "key": "fixture_mass", "label": "고정구 질량", %s "unit": "kg", "status": "%s" },
                                  { "key": "frequency", "label": "주파수 범위", "min": 20, "max": 2000, "unit": "Hz", "status": "CONFIRMED" } ],
                  "schedule": { "desiredStartDate": "2026-12-20", "desiredEndDate": "2026-12-21" },
                  "equipment": { "id": 48 }, "notes": "" }
                """.formatted(filled ? ", \"readyDate\": \"2026-12-15\"" : "", filled ? "\"value\": 2," : "",
                filled ? "CONFIRMED" : "MISSING");
    }

    private static String period(OffsetDateTime start, OffsetDateTime end) {
        return "{ \"startAt\": \"%s\", \"endAt\": \"%s\" }".formatted(start, end);
    }

    private static java.util.List<String> actions(JsonNode detail) {
        java.util.List<String> list = new java.util.ArrayList<>();
        detail.path("availableActions").forEach(a -> list.add(a.asString()));
        return list;
    }

    /** 그 의뢰에 대해 받은 알림 수 (다른 테스트 메서드가 만든 알림은 세지 않음) */
    private long unread(String email, String type, long requestId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notifications n JOIN users u ON u.id = n.user_id
                WHERE u.email = ? AND n.type = ? AND n.related_request_id = ?
                """, Long.class, email, type, requestId);
    }

    private String token(String email, UserRole role) {
        var row = jdbc.queryForMap("SELECT id, organization_id FROM users WHERE email = ?", email);
        return jwtProvider.createAccessToken(((Number) row.get("id")).longValue(), role,
                row.get("organization_id") == null ? null : ((Number) row.get("organization_id")).longValue());
    }
}
