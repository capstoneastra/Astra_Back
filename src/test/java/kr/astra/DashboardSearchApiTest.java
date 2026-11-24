package kr.astra;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import kr.astra.TestApi.Res;
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
 * 대시보드 (api-spec §10) 와 자연어 검색 (§2 GET /search). 장비를 바꾸지 않는 조회 테스트라
 * EquipmentApiTest 와 같은 DB(seed 그대로)를 씁니다. 의뢰(BE-B)는 미연결이며 상담(BE-C)은 연결되어 있지만 seed 상담은 없습니다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardSearchApiTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtProvider jwtProvider;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, null);
    }

    // ------------------------------------------------------------ GET /dashboard/equipment-stats

    @Test
    void 장비_통계는_지역_유형_시험유형_기관별_수를_준다() throws Exception {
        Res res = api.get("/api/v1/dashboard/equipment-stats", token("company@astra.kr", UserRole.COMPANY_USER));
        assertThat(res.status()).isEqualTo(200);
        JsonNode d = res.data();
        assertThat(d.path("total").asLong()).isEqualTo(activeEquipment());
        // 지역 가나다순. V5 로 진주 = 5 + 31, V8 시연 장비가 사천·창원·고흥에 추가
        assertThat(d.path("byRegion").toString()).startsWith("{\"경남\":1,\"고흥\":40,\"광주\":1,\"대전\":32,");
        assertThat(d.path("byRegion").path("진주").asLong()).isEqualTo(36);
        assertThat(d.path("byRegion").path("사천").asLong()).isEqualTo(154);
        assertThat(d.path("byRegion").path("창원").asLong()).isEqualTo(74);
        long byType = 0;
        for (JsonNode n : d.path("byEquipmentType")) {
            byType += n.asLong();
        }
        assertThat(byType).isEqualTo(activeEquipment());
        assertThat(d.path("byTestType").size()).isEqualTo(15);
        assertThat(d.path("byTestType").get(0).path("code").asString()).isEqualTo("ACOUSTIC_NOISE");
        JsonNode first = d.path("institutions").get(0);
        // 장비 수 많은 순, 같으면 가나다순: 늘해랑대학교·온새미로 55대
        assertThat(first.path("name").asString()).isEqualTo("늘해랑대학교 항공우주공동기기원");
        assertThat(first.path("equipmentCount").asLong()).isEqualTo(55);
        assertThat(first.path("verifiedCount").asLong()).isZero();
        assertThat(first.path("verificationRate").asDouble()).isZero();
        assertThat(d.path("institutions").size()).isEqualTo(equipmentOwners());
    }

    // ------------------------------------------------------------ GET /dashboard/summary

    @Test
    void 기업_요약은_의뢰_상태_10개를_0으로_채우고_연결_안_된_데이터를_알려준다() throws Exception {
        JsonNode d = api.get("/api/v1/dashboard/summary", token("company@astra.kr", UserRole.COMPANY_USER)).data();
        assertThat(d.path("role").asString()).isEqualTo("COMPANY_USER");
        assertThat(d.path("requestCounts").size()).isEqualTo(10);
        assertThat(d.path("requestCounts").path("SUPPLEMENT_REQUESTED").asLong()).isZero();
        assertThat(d.path("actionRequired").size()).isZero();
        assertThat(d.path("recentChats").size()).isZero();
        assertThat(d.path("equipmentStats").path("total").asLong()).isEqualTo(activeEquipment());
        assertThat(d.path("equipmentStats").path("byRegion").path("진주").asLong()).isEqualTo(36);
        assertThat(d.path("dataAvailable").path("requests").asBoolean()).isTrue(); // BE-B RequestDashboardQuery 연결됨
        assertThat(d.path("dataAvailable").path("chats").asBoolean()).isTrue();
    }

    @Test
    void view_로_의뢰자_화면과_장비_보유_조직_화면을_고른다() throws Exception {
        String company = token("company@astra.kr", UserRole.COMPANY_USER);
        String manager1 = token("lab1@astra.kr", UserRole.INSTITUTION_MANAGER);

        // 안 보내면 가입 유형 기본 화면
        assertThat(api.get("/api/v1/dashboard/summary", company).data().path("view").asString()).isEqualTo("REQUESTER");
        assertThat(api.get("/api/v1/dashboard/summary", manager1).data().path("view").asString()).isEqualTo("PROVIDER");

        // 기업도 장비 보유 조직 화면을 본다 (등록한 장비가 없으면 0)
        JsonNode provider = api.get("/api/v1/dashboard/summary?view=PROVIDER", company).data();
        assertThat(provider.path("role").asString()).isEqualTo("COMPANY_USER");
        assertThat(provider.path("view").asString()).isEqualTo("PROVIDER");
        assertThat(provider.path("institution").path("name").asString()).isEqualTo("가상 A기업");
        assertThat(provider.path("requestCounts").size()).isEqualTo(10);
        assertThat(provider.path("equipmentNeedsUpdate").path("total").asLong()).isZero();
        assertThat(provider.has("pendingReview")).isTrue();
        assertThat(provider.has("recentChats")).isFalse();

        // 기관·대학 회원도 의뢰자 화면을 본다
        JsonNode requester = api.get("/api/v1/dashboard/summary?view=REQUESTER", manager1).data();
        assertThat(requester.path("role").asString()).isEqualTo("INSTITUTION_MANAGER");
        assertThat(requester.path("view").asString()).isEqualTo("REQUESTER");
        assertThat(requester.path("requestCounts").size()).isEqualTo(10);
        assertThat(requester.has("actionRequired")).isTrue();
        assertThat(requester.has("recentChats")).isTrue();
        assertThat(requester.has("institution")).isFalse();

        // 관리자는 view 와 무관하게 관리자 요약
        JsonNode admin = api.get("/api/v1/dashboard/summary?view=REQUESTER", token("admin@astra.kr", UserRole.ADMIN))
                .data();
        assertThat(admin.path("view").asString()).isEqualTo("ADMIN");
        assertThat(admin.has("pendingUsers")).isTrue();
        assertThat(api.get("/api/v1/dashboard/summary?view=NOPE", company).status()).isEqualTo(400);
    }

    @Test
    void 기관_요약은_소속_기관과_갱신_필요_장비_수를_준다() throws Exception {
        JsonNode d = api.get("/api/v1/dashboard/summary", token("lab1@astra.kr", UserRole.INSTITUTION_MANAGER))
                .data();
        assertThat(d.path("role").asString()).isEqualTo("INSTITUTION_MANAGER");
        assertThat(d.path("institution").path("id").asLong()).isEqualTo(2);
        assertThat(d.path("institution").path("name").asString()).isEqualTo("미리내우주환경시험센터");
        // seed 장비 13대 모두 기관 확인 전
        assertThat(d.path("equipmentNeedsUpdate").path("total").asLong()).isEqualTo(13);
        assertThat(d.path("equipmentNeedsUpdate").path("notConfirmed").asLong()).isEqualTo(13);
        assertThat(d.path("equipmentNeedsUpdate").path("outdated").asLong()).isZero();
        assertThat(d.path("pendingReview").size()).isZero();
        assertThat(d.path("upcomingReservations").size()).isZero();
        assertThat(d.path("dataAvailable").has("chats")).isFalse();
    }

    @Test
    void 관리자_요약은_승인_대기_수와_기관별_확인_비율을_준다() throws Exception {
        JsonNode d = api.get("/api/v1/dashboard/summary", token("admin@astra.kr", UserRole.ADMIN)).data();
        assertThat(d.path("role").asString()).isEqualTo("ADMIN");
        long pending = jdbc.queryForObject("SELECT count(*) FROM users WHERE status = 'PENDING'", Long.class);
        assertThat(d.path("pendingUsers").asLong()).isEqualTo(pending).isGreaterThanOrEqualTo(1); // pending@astra.kr
        assertThat(d.path("pendingInstitutions").asLong()).isEqualTo(jdbc.queryForObject(
                "SELECT count(*) FROM organizations WHERE org_type IN ('INSTITUTION', 'UNIVERSITY') AND NOT is_verified",
                Long.class));
        assertThat(d.path("requestCounts").size()).isEqualTo(10);
        assertThat(d.path("institutionVerification").size()).isEqualTo(equipmentOwners());
        // 시험유형별 수요(작성 중 뺀 의뢰)·공급(활성 장비): 15개 전부
        JsonNode byTestType = d.path("byTestType");
        assertThat(byTestType.size()).isEqualTo(15);
        JsonNode vibration = null;
        for (JsonNode t : byTestType) {
            if (t.path("code").asString().equals("VIBRATION")) {
                vibration = t;
            }
        }
        assertThat(vibration).isNotNull();
        assertThat(vibration.path("nameKo").asString()).isEqualTo(jdbc.queryForObject(
                "SELECT name_ko FROM test_types WHERE code = 'VIBRATION'", String.class));
        assertThat(vibration.path("equipmentCount").asLong()).isEqualTo(jdbc.queryForObject("""
                SELECT count(*) FROM equipment_test_types t JOIN equipment e ON e.id = t.equipment_id
                WHERE e.is_active AND t.test_type_code = 'VIBRATION'""", Long.class)).isPositive();
        assertThat(vibration.path("requestCount").asLong()).isEqualTo(jdbc.queryForObject("""
                SELECT count(*) FROM test_requests r
                JOIN test_request_revisions v ON v.request_id = r.id AND v.revision_no = r.current_revision_no
                WHERE r.status <> 'DRAFT' AND v.content -> 'test' ->> 'testTypeCode' = 'VIBRATION'""", Long.class));
        assertThat(api.get("/api/v1/dashboard/summary", null).code()).isEqualTo("AUTH_REQUIRED");
    }

    // ------------------------------------------------------------ GET /search

    @Test
    void 시연_문장은_진동시험과_질량_주파수_조건으로_해석된다() throws Exception {
        Res res = search("위성 전장품 15kg, 20~2000Hz 진동시험");
        assertThat(res.status()).isEqualTo(200);
        JsonNode i = res.data().path("interpreted");
        assertThat(i.path("interpreter").asString()).isEqualTo("KEYWORD");
        assertThat(i.path("testTypes").toString()).isEqualTo("[\"VIBRATION\"]");
        assertThat(i.path("conditions").toString()).isEqualTo(
                "[{\"key\":\"sample_mass\",\"value\":15,\"unit\":\"kg\"},"
                        + "{\"key\":\"frequency\",\"min\":20,\"max\":2000,\"unit\":\"Hz\"}]");
        assertThat(i.path("regions").size()).isZero();
        assertThat(res.data().path("testTypes").get(0).path("nameKo").asString()).isEqualTo("진동시험");
        // 외부 id 순: 기존 진동시험기 4대 다음에 V8 시연 진동시험기
        List<String> ids = externalIds(res);
        assertThat(ids.subList(0, 4)).containsExactly("ASTRA-CAND-0044", "ASTRA-CAND-0045", "ASTRA-CAND-0047",
                "ASTRA-CAND-0048");
        assertThat(ids).allMatch(id -> hasTestType(id, "VIBRATION"));
    }

    @Test
    void 지역이_있으면_그_지역_장비만_찾는다() throws Exception {
        Res res = search("대전 진동시험");
        assertThat(res.data().path("interpreted").path("regions").toString()).isEqualTo("[\"대전\"]");
        assertThat(externalIds(res)).containsExactly("ASTRA-CAND-0044", "ASTRA-CAND-0045", "ASTRA-CAND-0048");
        // 다른 지역(사천·창원·고흥)의 시연 진동시험기는 나오지 않음
    }

    @Test
    void 시험유형을_못_찾으면_장비명_모델로_찾고_빈_검색어는_400() throws Exception {
        Res res = search("EDS5000");
        assertThat(res.data().path("interpreted").path("testTypes").size()).isZero();
        assertThat(externalIds(res)).contains("ASTRA-CAND-0048");

        assertThat(search(" ").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(api.get("/api/v1/search", token("company@astra.kr", UserRole.COMPANY_USER)).code())
                .isEqualTo("VALIDATION_FAILED");
    }

    // ------------------------------------------------------------ helpers

    private Res search(String q) throws Exception {
        return api.get("/api/v1/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8),
                token("company@astra.kr", UserRole.COMPANY_USER));
    }

    private String token(String email, UserRole role) {
        var row = jdbc.queryForMap("SELECT id, organization_id FROM users WHERE email = ?", email);
        return jwtProvider.createAccessToken(((Number) row.get("id")).longValue(), role,
                row.get("organization_id") == null ? null : ((Number) row.get("organization_id")).longValue());
    }

    private long activeEquipment() {
        return jdbc.queryForObject("SELECT count(*) FROM equipment WHERE is_active", Long.class);
    }

    /** 활성 장비를 1대 이상 가진 조직 수 */
    private long equipmentOwners() {
        return jdbc.queryForObject("SELECT count(DISTINCT institution_id) FROM equipment WHERE is_active", Long.class);
    }

    private boolean hasTestType(String externalId, String code) {
        return jdbc.queryForObject("SELECT count(*) FROM equipment e JOIN equipment_test_types t ON t.equipment_id = e.id "
                + "WHERE e.external_id = ? AND t.test_type_code = ?", Long.class, externalId, code) > 0;
    }

    private static List<String> externalIds(Res res) {
        List<String> ids = new ArrayList<>();
        res.data().path("equipment").forEach(n -> ids.add(n.path("externalId").asString()));
        return ids;
    }
}
