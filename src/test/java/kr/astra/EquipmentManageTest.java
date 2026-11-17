package kr.astra;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import kr.astra.TestApi.Res;
import kr.astra.TestMailConfiguration.CapturingEmailSender;
import kr.astra.equipment.service.EquipmentUpdateReminder;
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
 * 기관 장비 관리 (api-spec §2-1) + 관리자 장비 소속 변경 (§12).
 * 장비를 만들고 바꾸므로 장비 수를 검사하는 EquipmentApiTest 와 다른 DB(테스트 컨텍스트)를 씁니다.
 * 시연 계정: manager1 = 미리내우주환경시험센터(id 2, 장비 48 보유), manager2 = 다온신뢰성시험센터(id 6).
 */
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EquipmentManageTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    CapturingEmailSender mail;

    @Autowired
    DataSource dataSource;

    @Autowired
    EquipmentUpdateReminder reminder;

    private TestApi api;
    private String manager1;
    private String manager2;
    private String admin;
    private String company;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, mail);
        manager1 = token("manager1@astra.demo", UserRole.INSTITUTION_MANAGER);
        manager2 = token("manager2@astra.demo", UserRole.INSTITUTION_MANAGER);
        admin = token("admin@astra.demo", UserRole.ADMIN);
        company = token("company@astra.demo", UserRole.COMPANY_USER);
    }

    // ------------------------------------------------------------ 등록

    @Test
    void 기관_담당자가_장비를_등록하면_ASTRA_EQ_외부id로_소속_기관에_들어가고_이력이_남는다() throws Exception {
        String name = "테스트 진동시험기 " + UUID.randomUUID();
        Res created = api.post("/api/v1/equipment", createBody(name), manager1);
        assertThat(created.status()).isEqualTo(201);
        long id = created.data().path("id").asLong();
        assertThat(created.data().path("externalId").asString()).isEqualTo("ASTRA-EQ-%04d".formatted(id));
        assertThat(created.data().path("verificationStatus").asString()).isEqualTo("INSTITUTION_VERIFIED");

        JsonNode d = api.get("/api/v1/equipment/" + id, manager1).data();
        assertThat(d.path("institution").path("id").asLong()).isEqualTo(2);
        assertThat(d.path("region").asString()).isEqualTo("대전"); // 지역을 안 보내면 기관 지역
        assertThat(d.path("isMock").asBoolean()).isFalse();
        assertThat(d.path("testTypes").toString()).isEqualTo("[\"VIBRATION\"]");
        assertThat(d.path("usage").path("feeAmount").asInt()).isEqualTo(300000);
        assertThat(d.path("specs").size()).isEqualTo(2);
        assertThat(d.path("specs").get(0).path("sourceType").asString()).isEqualTo("REAL");
        assertThat(d.path("specs").get(0).path("verificationStatus").asString()).isEqualTo("VERIFIED");

        JsonNode logs = api.get("/api/v1/equipment/" + id + "/change-logs", manager1).data().path("content");
        assertThat(logs.size()).isEqualTo(1);
        assertThat(logs.get(0).path("action").asString()).isEqualTo("CREATE");
        assertThat(logs.get(0).path("changedBy").path("name").asString()).isEqualTo("김담당");
        assertThat(logs.get(0).path("changes").path("name").path("after").asString()).isEqualTo(name);
        assertThat(logs.get(0).path("changes").path("specs").path("after").size()).isEqualTo(2);
    }

    @Test
    void 관리자는_기관을_지정해야_등록되고_다른_기관으로는_403() throws Exception {
        assertThat(api.post("/api/v1/equipment", createBody("관리자 등록 " + UUID.randomUUID()), admin).code())
                .isEqualTo("VALIDATION_FAILED");
        Res byAdmin = api.post("/api/v1/equipment",
                createBody("관리자 등록 " + UUID.randomUUID()).replace("\"equipmentType\"", "\"institutionId\": 6, \"equipmentType\""),
                admin);
        assertThat(byAdmin.status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT institution_id FROM equipment WHERE id = ?", Long.class,
                byAdmin.data().path("id").asLong())).isEqualTo(6);

        assertThat(api.post("/api/v1/equipment",
                createBody("남의 기관 " + UUID.randomUUID()).replace("\"equipmentType\"", "\"institutionId\": 6, \"equipmentType\""),
                manager1).code()).isEqualTo("FORBIDDEN");
        assertThat(api.post("/api/v1/equipment",
                createBody("남의 기관 " + UUID.randomUUID()).replace("\"equipmentType\"", "\"institutionId\": 6, \"equipmentType\""),
                company).code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void 기업도_자기_조직_장비를_등록하고_관리하며_장비가_있는_동안_기관_목록에_나온다() throws Exception {
        long companyOrgId = jdbc.queryForObject(
                "SELECT organization_id FROM users WHERE email = 'company@astra.demo'", Long.class);
        // 장비가 없는 기업은 기관 목록·상세에 나오지 않는다
        assertThat(institutionIds()).doesNotContain(companyOrgId);
        assertThat(api.get("/api/v1/institutions/" + companyOrgId, company).code()).isEqualTo("NOT_FOUND");

        Res created = api.post("/api/v1/equipment", createBody("기업 보유 장비 " + UUID.randomUUID()), company);
        assertThat(created.status()).isEqualTo(201);
        long id = created.data().path("id").asLong();
        try {
            JsonNode d = api.get("/api/v1/equipment/" + id, manager1).data();
            assertThat(d.path("institution").path("id").asLong()).isEqualTo(companyOrgId);
            assertThat(d.path("institution").path("name").asString()).isEqualTo("가상 A기업");

            assertThat(externalIds(myEquipment(company))).containsExactly("ASTRA-EQ-%04d".formatted(id));
            assertThat(institutionIds()).contains(companyOrgId);
            JsonNode org = api.get("/api/v1/institutions/" + companyOrgId, manager1).data();
            assertThat(org.path("orgType").asString()).isEqualTo("COMPANY");
            assertThat(org.path("equipmentCount").asLong()).isEqualTo(1);

            // 수정은 보유 기업만. 다른 조직(기관 담당자)은 403
            assertThat(api.send("PATCH", "/api/v1/equipment/" + id, "{ \"model\": \"C-100\" }", manager1).code())
                    .isEqualTo("FORBIDDEN");
            Res patched = api.send("PATCH", "/api/v1/equipment/" + id, "{ \"model\": \"C-100\" }", company);
            assertThat(patched.status()).isEqualTo(200);
            assertThat(patched.data().path("model").asString()).isEqualTo("C-100");
            // 기업은 기관 장비를 고칠 수 없다
            assertThat(api.send("PATCH", "/api/v1/equipment/48", "{ \"model\": \"x\" }", company).code())
                    .isEqualTo("FORBIDDEN");
        } finally {
            assertThat(api.send("DELETE", "/api/v1/equipment/" + id, null, company).status()).isEqualTo(200);
        }
        assertThat(institutionIds()).doesNotContain(companyOrgId);
    }

    @Test
    void 입력값이_잘못되면_필드별_400() throws Exception {
        assertThat(firstField(api.post("/api/v1/equipment", """
                { "name": "유형 누락", "equipmentType": "TEST" }""", manager1)))
                .isEqualTo("testTypes");
        assertThat(firstField(api.send("PUT", "/api/v1/equipment/48/test-types", "{\"testTypes\":[]}", manager1)))
                .isEqualTo("testTypes");
        assertThat(firstField(api.post("/api/v1/equipment", """
                { "name": "x", "equipmentType": "TEST", "specs": [ { "key": "a", "label": "값 없음" } ] }""", manager1)))
                .isEqualTo("specs[0].value");
        assertThat(firstField(api.post("/api/v1/equipment", """
                { "name": "x", "equipmentType": "TEST", "specs": [ { "key": "f", "label": "범위", "min": 10, "max": 5 } ] }""",
                manager1))).isEqualTo("specs[0].min");
        assertThat(firstField(api.post("/api/v1/equipment", """
                { "name": "x", "equipmentType": "TEST", "specs": [ { "key": "Bad-Key", "label": "키", "value": 1 } ] }""",
                manager1))).isEqualTo("specs[0].key");
        assertThat(firstField(api.post("/api/v1/equipment", """
                { "name": "x", "equipmentType": "TEST", "testTypes": [ { "code": "NOPE" } ] }""", manager1)))
                .isEqualTo("testTypes[0].code");
    }

    // ------------------------------------------------------------ 수정·사양·시험유형·확인·비활성화

    @Test
    void 수정하면_보낸_값만_바뀌고_전후_값이_이력에_남는다() throws Exception {
        long id = create(manager1);
        Res patched = api.send("PATCH", "/api/v1/equipment/" + id, """
                { "model": "V-900", "usage": { "feeAmount": 450000, "availability": "AVAILABLE" } }""", manager1);
        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.data().path("model").asString()).isEqualTo("V-900");
        assertThat(patched.data().path("availability").asString()).isEqualTo("AVAILABLE");
        assertThat(patched.data().path("usage").path("feeAmount").asInt()).isEqualTo(450000);
        assertThat(patched.data().path("usage").path("feeText").asString()).isEqualTo("300,000원/일"); // 안 보낸 값은 그대로

        JsonNode log = api.get("/api/v1/equipment/" + id + "/change-logs", manager1).data().path("content").get(0);
        assertThat(log.path("action").asString()).isEqualTo("UPDATE");
        assertThat(log.path("changes").path("feeAmount").path("before").asInt()).isEqualTo(300000);
        assertThat(log.path("changes").path("feeAmount").path("after").asInt()).isEqualTo(450000);
        assertThat(log.path("changes").has("name")).isFalse();

        assertThat(api.send("PATCH", "/api/v1/equipment/" + id, "{ \"model\": \"x\" }", manager2).code())
                .isEqualTo("FORBIDDEN");
    }

    @Test
    void 실제_사양은_REAL만_교체되고_MOCK은_그대로다() throws Exception {
        // 시연 장비 48 (MOCK 4개)에 manager1 이 실제 주파수 범위를 입력
        Res put = api.send("PUT", "/api/v1/equipment/48/specs", """
                { "specs": [ { "key": "frequency", "label": "주파수 범위", "min": 5, "max": 3000, "unit": "Hz" } ] }""",
                manager1);
        assertThat(put.status()).isEqualTo(200);
        JsonNode specs = put.data().path("specs");
        assertThat(specs.size()).isEqualTo(5);
        assertThat(specs.get(0).path("sourceType").asString()).isEqualTo("REAL");
        assertThat(specs.get(0).path("min").asInt()).isEqualTo(5);
        assertThat(specs.get(1).path("key").asString()).isEqualTo("frequency");
        assertThat(specs.get(1).path("sourceType").asString()).isEqualTo("MOCK");
        assertThat(jdbc.queryForObject("SELECT spec_verification_status FROM equipment WHERE id = 48", String.class))
                .isEqualTo("VERIFIED");

        Res cleared = api.send("PUT", "/api/v1/equipment/48/specs", "{ \"specs\": [] }", manager1);
        assertThat(cleared.data().path("specs").size()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment_specs WHERE equipment_id = 48 AND source_type = 'MOCK'",
                Long.class)).isEqualTo(4);

        JsonNode log = api.get("/api/v1/equipment/48/change-logs", manager1).data().path("content").get(0);
        assertThat(log.path("action").asString()).isEqualTo("SPECS_UPDATE");
        assertThat(log.path("changes").path("specs").path("before").size()).isEqualTo(1);
        assertThat(log.path("changes").path("specs").path("after").size()).isZero();

        assertThat(api.send("PUT", "/api/v1/equipment/48/specs", "{ \"specs\": [] }", manager2).code())
                .isEqualTo("FORBIDDEN");
    }

    @Test
    void 가능_시험유형을_교체한다() throws Exception {
        long id = create(manager1);
        Res put = api.send("PUT", "/api/v1/equipment/" + id + "/test-types", """
                { "testTypes": [ { "code": "TEMPERATURE_HUMIDITY", "estCostMin": 100000, "estCostMax": 200000, "estDurationDays": 1 },
                                 { "code": "VIBRATION" } ] }""", manager1);
        assertThat(put.status()).isEqualTo(200);
        assertThat(put.data().path("testTypes").toString()).isEqualTo("[\"TEMPERATURE_HUMIDITY\",\"VIBRATION\"]");
        assertThat(jdbc.queryForObject("SELECT est_cost_max FROM equipment_test_types WHERE equipment_id = ? "
                + "AND test_type_code = 'TEMPERATURE_HUMIDITY'", Integer.class, id)).isEqualTo(200000);

        assertThat(firstField(api.send("PUT", "/api/v1/equipment/" + id + "/test-types", """
                { "testTypes": [ { "code": "VIBRATION" }, { "code": "VIBRATION" } ] }""", manager1)))
                .isEqualTo("testTypes[1].code");
    }

    @Test
    void 일반정보_확인과_가용상태_확인은_별개다() throws Exception {
        // 다른 테스트(사양 저장)가 먼저 돌아 확인 상태가 됐을 수 있으므로 seed 값으로 되돌리고 시작
        jdbc.update("UPDATE equipment SET verification_status = 'LISTING_CONFIRMED', info_updated_at = now() WHERE id = 48");
        JsonNode before = findById(myEquipment(manager1), 48);
        assertThat(before.path("needsUpdate").asBoolean()).isTrue();
        assertThat(before.path("updateReasons").toString()).contains("NOT_CONFIRMED");

        Res confirmed = api.post("/api/v1/equipment/48/confirm", null, manager1);
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.data().path("verificationStatus").asString()).isEqualTo("INSTITUTION_VERIFIED");
        JsonNode after = findById(myEquipment(manager1), 48);
        assertThat(after.path("needsUpdate").asBoolean()).isTrue();
        assertThat(after.path("availabilityNeedsConfirmation").asBoolean()).isTrue();
        assertThat(after.path("daysSinceUpdate").asLong()).isZero();

        // 90일 넘게 갱신 안 하면 OUTDATED
        jdbc.update("UPDATE equipment SET info_updated_at = now() - interval '100 days' WHERE id = 48");
        JsonNode outdated = findById(myEquipment(manager1), 48);
        assertThat(outdated.path("updateReasons").toString()).isEqualTo("[\"OUTDATED\"]");
        assertThat(outdated.path("daysSinceUpdate").asLong()).isGreaterThanOrEqualTo(99);
        api.post("/api/v1/equipment/48/confirm", null, manager1);
        api.send("PATCH", "/api/v1/equipment/48", "{\"usage\":{\"availability\":\"AVAILABLE\",\"availabilityNote\":\"기관 담당자 확인\"}}", manager1);
        assertThat(findById(myEquipment(manager1),48).path("availabilityNeedsConfirmation").asBoolean()).isFalse();
        jdbc.update("UPDATE equipment SET availability='UNKNOWN',availability_updated_at=NULL,availability_updated_by=NULL WHERE id=48");
    }

    @Test
    void 우리_기관_장비_목록은_소속_기관만_보이고_관리자는_기관을_지정한다() throws Exception {
        JsonNode mine = api.get("/api/v1/institutions/me/equipment?size=100", manager2).data();
        mine.path("content").forEach(e -> assertThat(e.path("externalId").asString()).isNotEqualTo("ASTRA-CAND-0048"));
        assertThat(externalIds(mine)).contains("ASTRA-CAND-0044", "ASTRA-CAND-0045");

        assertThat(api.get("/api/v1/institutions/me/equipment", admin).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(api.get("/api/v1/institutions/me/equipment?institutionId=6", admin).status()).isEqualTo(200);
        // 기업도 소속 조직 장비 목록을 본다 (등록한 장비가 없으면 빈 목록)
        Res companyList = api.get("/api/v1/institutions/me/equipment", company);
        assertThat(companyList.status()).isEqualTo(200);
        assertThat(companyList.data().path("content").size()).isZero();
    }

    @Test
    void 비활성화하면_목록과_상세에서_사라지지만_이력은_볼_수_있다() throws Exception {
        long id = create(manager1);
        assertThat(api.send("DELETE", "/api/v1/equipment/" + id, null, manager2).code()).isEqualTo("FORBIDDEN");
        assertThat(api.send("DELETE", "/api/v1/equipment/" + id, null, manager1).status()).isEqualTo(200);

        assertThat(api.get("/api/v1/equipment/" + id, manager1).code()).isEqualTo("EQUIPMENT_NOT_FOUND");
        assertThat(externalIds(myEquipment(manager1))).doesNotContain("ASTRA-EQ-%04d".formatted(id));
        assertThat(api.send("PATCH", "/api/v1/equipment/" + id, "{ \"model\": \"x\" }", manager1).code())
                .isEqualTo("EQUIPMENT_NOT_FOUND");
        JsonNode log = api.get("/api/v1/equipment/" + id + "/change-logs", manager1).data().path("content").get(0);
        assertThat(log.path("action").asString()).isEqualTo("DEACTIVATE");
    }

    // ------------------------------------------------------------ 관리자 소속 변경

    @Test
    void 기관이_입력한_요금과_예상_비용이_비교표에_나온다() throws Exception {
        long id = create(manager1); // 300,000원/일, 1~2일, 가동률 60, 진동시험 30만~60만원·2일
        JsonNode d = api.post("/api/v1/equipment/compare",
                "{ \"equipmentIds\": [%d, 48], \"testType\": \"VIBRATION\" }".formatted(id), company).data();
        JsonNode mine = d.path("usage").get(0);
        assertThat(mine.path("feeText").asString()).isEqualTo("300,000원/일");
        assertThat(mine.path("feeAmount").asInt()).isEqualTo(300000);
        assertThat(mine.path("feeUnit").asString()).isEqualTo("DAY");
        assertThat(mine.path("durationText").asString()).isEqualTo("1~2일");
        assertThat(mine.path("utilizationRate").asInt()).isEqualTo(60);
        assertThat(mine.path("availability").asString()).isEqualTo("AVAILABLE");
        assertThat(mine.path("accessScope").asString()).isEqualTo("EXTERNAL_ALLOWED");
        assertThat(mine.path("verificationStatus").asString()).isEqualTo("INSTITUTION_VERIFIED");
        JsonNode estimate = mine.path("testTypeEstimate");
        assertThat(estimate.path("supported").asBoolean()).isTrue();
        assertThat(estimate.path("estCostMin").asInt()).isEqualTo(300000);
        assertThat(estimate.path("estCostMax").asInt()).isEqualTo(600000);
        assertThat(estimate.path("estDurationDays").asInt()).isEqualTo(2);
        // 기관이 넣은 사양은 REAL, 시연 장비 48 은 MOCK 이 섞여 DEMO 표시
        assertThat(d.path("usedMockData").asBoolean()).isTrue();
    }

    @Test
    void 관리자는_장비를_다른_기관으로_옮길_수_있다() throws Exception {
        long id = create(manager1);
        assertThat(api.post("/api/v1/admin/equipment/" + id + "/transfer", "{ \"institutionId\": 6 }", manager1).code())
                .isEqualTo("FORBIDDEN");
        assertThat(api.post("/api/v1/admin/equipment/" + id + "/transfer", "{ \"institutionId\": 2 }", admin).code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(api.post("/api/v1/admin/equipment/" + id + "/transfer", "{ \"institutionId\": 999999 }", admin).code())
                .isEqualTo("VALIDATION_FAILED");

        Res moved = api.post("/api/v1/admin/equipment/" + id + "/transfer",
                "{ \"institutionId\": 6, \"reason\": \"수집 오류\" }", admin);
        assertThat(moved.status()).isEqualTo(200);
        assertThat(moved.data().path("institution").path("id").asLong()).isEqualTo(6);
        JsonNode log = api.get("/api/v1/equipment/" + id + "/change-logs", admin).data().path("content").get(0);
        assertThat(log.path("action").asString()).isEqualTo("TRANSFER");
        assertThat(log.path("changes").path("institutionId").path("before").asLong()).isEqualTo(2);
        assertThat(log.path("changes").path("reason").asString()).isEqualTo("수집 오류");
        // 이제 manager1 은 수정 못 하고 manager2 가 함
        assertThat(api.send("PATCH", "/api/v1/equipment/" + id, "{ \"model\": \"x\" }", manager1).code())
                .isEqualTo("FORBIDDEN");
        assertThat(api.send("PATCH", "/api/v1/equipment/" + id, "{ \"model\": \"x\" }", manager2).status())
                .isEqualTo(200);
    }

    @Test
    void 관리자_소속_변경은_행을_잠가_동시에_들어온_담당자_수정을_되돌리지_않는다() throws Exception {
        long id = create(manager1);
        // 다른 트랜잭션이 먼저 행을 잠그고 수정 중
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.prepareStatement("SELECT id FROM equipment WHERE id = ? FOR UPDATE")) {
                st.setLong(1, id);
                st.executeQuery();
            }
            CompletableFuture<Res> transfer = CompletableFuture.supplyAsync(() -> {
                try {
                    return api.post("/api/v1/admin/equipment/" + id + "/transfer", "{ \"institutionId\": 6 }", admin);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(1000); // 소속 변경 요청이 행 잠금에서 기다리도록
            try (var st = c.prepareStatement("UPDATE equipment SET model = 'CONCURRENT' WHERE id = ?")) {
                st.setLong(1, id);
                st.executeUpdate();
            }
            c.commit();
            assertThat(transfer.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT model FROM equipment WHERE id = ?", String.class, id))
                .isEqualTo("CONCURRENT");
        assertThat(jdbc.queryForObject("SELECT institution_id FROM equipment WHERE id = ?", Long.class, id))
                .isEqualTo(6);
    }

    @Test
    void 수정할_때_공백뿐인_장비명은_400() throws Exception {
        long id = create(manager1);
        assertThat(firstField(api.send("PATCH", "/api/v1/equipment/" + id, "{ \"name\": \"   \" }", manager1)))
                .isEqualTo("name");
        assertThat(jdbc.queryForObject("SELECT name FROM equipment WHERE id = ?", String.class, id)).isNotBlank();
    }

    @Test
    void 대시보드_갱신_필요_수는_기관_장비_목록의_needsUpdate_수와_같다() throws Exception {
        long id = create(manager1); // 기관 확인 + 가용 상태 확인 → 갱신 필요 아님
        // 일반 정보는 확인했지만 가용 상태는 확인이 필요한 장비
        jdbc.update("UPDATE equipment SET availability = 'UNKNOWN' WHERE id = ?", id);
        JsonNode list = myEquipment(manager1);
        assertThat(list.path("totalElements").asLong()).isLessThanOrEqualTo(100);
        assertThat(findById(list, id).path("needsUpdate").asBoolean()).isTrue();
        long needsUpdate = 0;
        for (JsonNode n : list.path("content")) {
            if (n.path("needsUpdate").asBoolean()) {
                needsUpdate++;
            }
        }
        JsonNode counts = api.get("/api/v1/dashboard/summary?view=PROVIDER", manager1).data()
                .path("equipmentNeedsUpdate");
        assertThat(counts.path("total").asLong()).isEqualTo(needsUpdate);
        assertThat(counts.path("total").asLong()).isGreaterThanOrEqualTo(
                Math.max(counts.path("notConfirmed").asLong(), counts.path("outdated").asLong()));
        jdbc.update("UPDATE equipment SET is_active = false WHERE id = ?", id);
    }

    @Test
    void 갱신_알림은_기관마다_요약_한_건만_보내고_다시_보내지_않는다() throws Exception {
        long manager1Id = jdbc.queryForObject("SELECT id FROM users WHERE email = 'manager1@astra.demo'", Long.class);
        // 다른 테스트가 남긴 대상이 섞이지 않도록 기존 장비는 모두 알림 보낸 것으로
        jdbc.update("UPDATE equipment SET availability_reminded_at = now() WHERE availability_reminded_at IS NULL");
        List<Long> ids = List.of(create(manager1), create(manager1), create(manager1));
        for (Long id : ids) {
            jdbc.update("UPDATE equipment SET availability_updated_at = now() - interval '100 days', "
                    + "availability_reminded_at = NULL WHERE id = ?", id);
        }
        long before = reminderCount(manager1Id);

        assertThat(reminder.remind()).isPositive();
        assertThat(reminderCount(manager1Id)).isEqualTo(before + 1);
        String title = jdbc.queryForObject("SELECT title FROM notifications WHERE user_id = ? "
                + "AND type = 'EQUIPMENT_UPDATE_REQUIRED' ORDER BY id DESC LIMIT 1", String.class, manager1Id);
        assertThat(title).contains("3대");
        for (Long id : ids) {
            assertThat(jdbc.queryForObject("SELECT availability_reminded_at IS NOT NULL FROM equipment WHERE id = ?",
                    Boolean.class, id)).isTrue();
        }

        // 같은 확인 주기에는 다시 보내지 않음
        assertThat(reminder.remind()).isZero();
        assertThat(reminderCount(manager1Id)).isEqualTo(before + 1);
    }

    private long reminderCount(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? "
                + "AND type = 'EQUIPMENT_UPDATE_REQUIRED'", Long.class, userId);
    }

    // ------------------------------------------------------------ helpers

    private String token(String email, UserRole role) {
        var row = jdbc.queryForMap("SELECT id, organization_id FROM users WHERE email = ?", email);
        return jwtProvider.createAccessToken(((Number) row.get("id")).longValue(), role,
                row.get("organization_id") == null ? null : ((Number) row.get("organization_id")).longValue());
    }

    private static String createBody(String name) {
        return """
                { "name": "%s", "model": "V-850", "manufacturer": "테스트 제조사", "category": "검사/시험장비",
                  "equipmentType": "TEST",
                  "usage": { "accessScope": "EXTERNAL_ALLOWED", "feeText": "300,000원/일", "feeAmount": 300000,
                             "feeUnit": "DAY", "durationText": "1~2일", "utilizationRate": 60, "availability": "AVAILABLE" },
                  "specs": [ { "key": "frequency", "label": "주파수 범위", "min": 5, "max": 3000, "unit": "Hz" },
                             { "key": "max_payload", "label": "최대 시료 질량", "value": 50, "unit": "kg" } ],
                  "testTypes": [ { "code": "VIBRATION", "estCostMin": 300000, "estCostMax": 600000, "estDurationDays": 2 } ] }
                """.formatted(name);
    }

    private List<Long> institutionIds() throws Exception {
        List<Long> ids = new ArrayList<>();
        api.get("/api/v1/institutions", admin).data().forEach(n -> ids.add(n.path("id").asLong()));
        return ids;
    }

    private long create(String token) throws Exception {
        Res res = api.post("/api/v1/equipment", createBody("테스트 장비 " + UUID.randomUUID()), token);
        assertThat(res.status()).as(res.body().toString()).isEqualTo(201);
        return res.data().path("id").asLong();
    }

    private JsonNode myEquipment(String token) throws Exception {
        return api.get("/api/v1/institutions/me/equipment?size=100", token).data();
    }

    private static JsonNode findById(JsonNode page, long id) {
        for (JsonNode n : page.path("content")) {
            if (n.path("id").asLong() == id) {
                return n;
            }
        }
        throw new AssertionError("id=" + id + " 항목이 없습니다");
    }

    private static List<String> externalIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("content").forEach(n -> ids.add(n.path("externalId").asString()));
        return ids;
    }

    private static String firstField(Res res) {
        assertThat(res.status()).as(res.body().toString()).isEqualTo(400);
        return res.details().path("fields").get(0).path("field").asString();
    }
}
