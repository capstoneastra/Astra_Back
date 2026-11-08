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
 * EquipmentApiTest 와 같은 DB(seed 그대로)를 씁니다. 의뢰(BE-B)·상담(BE-C) 구현은 아직 없어 0·빈 목록이어야 합니다.
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
        Res res = api.get("/api/v1/dashboard/equipment-stats", token("company@astra.demo", UserRole.COMPANY_USER));
        assertThat(res.status()).isEqualTo(200);
        JsonNode d = res.data();
        assertThat(d.path("total").asLong()).isEqualTo(71);
        // 지역 가나다순, V5 로 진주 = 5 + 31
        assertThat(d.path("byRegion").toString())
                .isEqualTo("{\"경남\":1,\"광주\":1,\"대전\":32,\"원주\":1,\"진주\":36}");
        long byType = 0;
        for (JsonNode n : d.path("byEquipmentType")) {
            byType += n.asLong();
        }
        assertThat(byType).isEqualTo(71);
        assertThat(d.path("byTestType").size()).isEqualTo(15);
        assertThat(d.path("byTestType").get(0).path("code").asString()).isEqualTo("ACOUSTIC_NOISE");
        JsonNode first = d.path("institutions").get(0);
        assertThat(first.path("name").asString()).isEqualTo("한국생산기술연구원 진주뿌리기술지원센터");
        assertThat(first.path("equipmentCount").asLong()).isEqualTo(31);
        assertThat(first.path("verifiedCount").asLong()).isZero();
        assertThat(first.path("verificationRate").asDouble()).isZero();
        assertThat(d.path("institutions").size()).isEqualTo(9);
    }

    // ------------------------------------------------------------ GET /dashboard/summary

    @Test
    void 기업_요약은_의뢰_상태_10개를_0으로_채우고_연결_안_된_데이터를_알려준다() throws Exception {
        JsonNode d = api.get("/api/v1/dashboard/summary", token("company@astra.demo", UserRole.COMPANY_USER)).data();
        assertThat(d.path("role").asString()).isEqualTo("COMPANY_USER");
        assertThat(d.path("requestCounts").size()).isEqualTo(10);
        assertThat(d.path("requestCounts").path("SUPPLEMENT_REQUESTED").asLong()).isZero();
        assertThat(d.path("actionRequired").size()).isZero();
        assertThat(d.path("recentChats").size()).isZero();
        assertThat(d.path("equipmentStats").path("total").asLong()).isEqualTo(71);
        assertThat(d.path("equipmentStats").path("byRegion").path("진주").asLong()).isEqualTo(36);
        assertThat(d.path("dataAvailable").path("requests").asBoolean()).isFalse();
        assertThat(d.path("dataAvailable").path("chats").asBoolean()).isFalse();
    }

    @Test
    void 기관_요약은_소속_기관과_갱신_필요_장비_수를_준다() throws Exception {
        JsonNode d = api.get("/api/v1/dashboard/summary", token("manager1@astra.demo", UserRole.INSTITUTION_MANAGER))
                .data();
        assertThat(d.path("role").asString()).isEqualTo("INSTITUTION_MANAGER");
        assertThat(d.path("institution").path("id").asLong()).isEqualTo(2);
        assertThat(d.path("institution").path("name").asString()).isEqualTo("대전테크노파크 우주·양자산업센터");
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
        JsonNode d = api.get("/api/v1/dashboard/summary", token("admin@astra.demo", UserRole.ADMIN)).data();
        assertThat(d.path("role").asString()).isEqualTo("ADMIN");
        long pending = jdbc.queryForObject("SELECT count(*) FROM users WHERE status = 'PENDING'", Long.class);
        assertThat(d.path("pendingUsers").asLong()).isEqualTo(pending).isGreaterThanOrEqualTo(1); // pending@astra.demo
        assertThat(d.path("pendingInstitutions").asLong()).isEqualTo(jdbc.queryForObject(
                "SELECT count(*) FROM organizations WHERE org_type = 'INSTITUTION' AND NOT is_verified", Long.class));
        assertThat(d.path("requestCounts").size()).isEqualTo(10);
        assertThat(d.path("institutionVerification").size()).isEqualTo(9);
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
        assertThat(externalIds(res)).containsExactly("ASTRA-CAND-0044", "ASTRA-CAND-0045", "ASTRA-CAND-0047",
                "ASTRA-CAND-0048");
    }

    @Test
    void 지역이_있으면_그_지역_장비만_찾는다() throws Exception {
        Res res = search("대전 진동시험");
        assertThat(res.data().path("interpreted").path("regions").toString()).isEqualTo("[\"대전\"]");
        assertThat(externalIds(res)).containsExactly("ASTRA-CAND-0044", "ASTRA-CAND-0045", "ASTRA-CAND-0048");
    }

    @Test
    void 시험유형을_못_찾으면_장비명_모델로_찾고_빈_검색어는_400() throws Exception {
        Res res = search("EDS5000");
        assertThat(res.data().path("interpreted").path("testTypes").size()).isZero();
        assertThat(externalIds(res)).contains("ASTRA-CAND-0048");

        assertThat(search(" ").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(api.get("/api/v1/search", token("company@astra.demo", UserRole.COMPANY_USER)).code())
                .isEqualTo("VALIDATION_FAILED");
    }

    // ------------------------------------------------------------ helpers

    private Res search(String q) throws Exception {
        return api.get("/api/v1/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8),
                token("company@astra.demo", UserRole.COMPANY_USER));
    }

    private String token(String email, UserRole role) {
        var row = jdbc.queryForMap("SELECT id, organization_id FROM users WHERE email = ?", email);
        return jwtProvider.createAccessToken(((Number) row.get("id")).longValue(), role,
                row.get("organization_id") == null ? null : ((Number) row.get("organization_id")).longValue());
    }

    private static List<String> externalIds(Res res) {
        List<String> ids = new ArrayList<>();
        res.data().path("equipment").forEach(n -> ids.add(n.path("externalId").asString()));
        return ids;
    }
}
