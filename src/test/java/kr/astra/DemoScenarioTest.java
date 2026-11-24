package kr.astra;

import static kr.astra.TestApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.astra.TestApi.Res;
import kr.astra.TestMailConfiguration.CapturingEmailSender;
import kr.astra.global.security.JwtProvider;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 시연 시나리오 "위성 전장품 15kg, 20~2000Hz 진동시험" 중 BE-A 단계 (docs/demo-scenario.md 1·2·3·4·8·9·12·18).
 * 한 테스트 안에서 시연 순서대로 이어서 호출합니다. BE-B·BE-C 단계는 각 담당자가 이어 붙입니다.
 */
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoScenarioTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    CapturingEmailSender mail;

    @Test
    void 시연_시나리오_BE_A_단계가_처음부터_끝까지_이어진다() throws Exception {
        TestApi api = new TestApi(port, mail);
        String admin = token("admin@astra.kr", UserRole.ADMIN);
        String manager1 = token("lab1@astra.kr", UserRole.INSTITUTION_MANAGER);

        // 1. 새 기업 가입 (이메일 인증) → 승인 전 로그인 403
        String email = uniqueEmail();
        Res signup = api.signupCompany(email, "시연 확인 기업 " + UUID.randomUUID(), "123-45-67890");
        assertThat(signup.status()).isEqualTo(201);
        assertThat(signup.data().path("status").asString()).isEqualTo("PENDING");
        assertThat(api.login(email).code()).isEqualTo("ACCOUNT_NOT_ACTIVE");

        // 2. 관리자가 승인 대기 목록에서 찾아 승인
        long userId = signup.data().path("userId").asLong();
        JsonNode pending = api.get("/api/v1/admin/users?status=PENDING&size=100", admin).data().path("content");
        assertThat(ids(pending)).contains(userId);
        assertThat(api.post("/api/v1/admin/users/" + userId + "/approve", null, admin).data().path("status").asString())
                .isEqualTo("ACTIVE");

        // 3. 기업 로그인 → 대시보드
        Res login = api.login(email);
        assertThat(login.status()).isEqualTo(200);
        String company = login.data().path("accessToken").asString();
        JsonNode summary = api.get("/api/v1/dashboard/summary", company).data();
        assertThat(summary.path("role").asString()).isEqualTo("COMPANY_USER");
        // 같은 DB 를 쓰는 EquipmentManageTest 가 장비를 등록할 수 있어 seed 71대가 아니라 실제 활성 장비 수로 비교
        assertThat(summary.path("equipmentStats").path("total").asLong()).isEqualTo(activeEquipment());

        // 4. 시연 문장으로 장비 검색
        Res search = api.get("/api/v1/search?q=" + URLEncoder.encode("위성 전장품 15kg, 20~2000Hz 진동시험",
                StandardCharsets.UTF_8), company);
        JsonNode interpreted = search.data().path("interpreted");
        assertThat(interpreted.path("testTypes").toString()).isEqualTo("[\"VIBRATION\"]");
        assertThat(interpreted.path("conditions").get(0).path("key").asString()).isEqualTo("sample_mass");
        assertThat(interpreted.path("conditions").get(1).path("key").asString()).isEqualTo("frequency");
        List<String> found = new ArrayList<>();
        search.data().path("equipment").forEach(e -> found.add(e.path("externalId").asString()));
        assertThat(found).contains("ASTRA-CAND-0048", "ASTRA-CAND-0044");

        // 8. 장비 상세 (MOCK → DEMO), 비교표에서 주파수 범위 차이, 유사 장비
        JsonNode detail = api.get("/api/v1/equipment/48", company).data();
        assertThat(detail.path("isMock").asBoolean()).isTrue();
        assertThat(detail.path("specs").get(0).path("sourceType").asString()).isEqualTo("MOCK");
        JsonNode compare = api.post("/api/v1/equipment/compare", "{ \"equipmentIds\": [48, 44] }", company).data();
        JsonNode frequency = compare.path("rows").get(0);
        assertThat(frequency.path("key").asString()).isEqualTo("frequency");
        assertThat(frequency.path("values").get(0).path("max").asInt()).isEqualTo(3000); // 48: 20~2000Hz 포함
        assertThat(frequency.path("values").get(1).path("max").asInt()).isEqualTo(1800); // 44: 상한 2000Hz 못 미침
        assertThat(compare.path("usedMockData").asBoolean()).isTrue();
        assertThat(api.get("/api/v1/equipment/48/similar", company).data().get(0).path("reasons").toString())
                .contains("같은 시험유형: 진동시험");

        // 9. 시험 정보 안내: V6 공인 요구를 포함한 조건 키와 필수/선택 구분, 가능 기관 3곳
        Res testTypeResponse = api.get("/api/v1/test-types/VIBRATION", company);
        assertThat(testTypeResponse.status()).isEqualTo(200);
        JsonNode testType = testTypeResponse.data();
        List<String> conditionKeys = new ArrayList<>();
        List<String> requiredKeys = new ArrayList<>();
        testType.path("conditionRules").forEach(rule -> {
            conditionKeys.add(rule.path("conditionKey").asString());
            if (rule.path("isRequired").asBoolean()) {
                requiredKeys.add(rule.path("conditionKey").asString());
            }
        });
        assertThat(conditionKeys).containsExactly("sample_mass", "fixture_mass", "frequency",
                "excitation_axis", "accredited_required");
        assertThat(requiredKeys).containsExactly("sample_mass", "fixture_mass", "frequency");
        // 진동시험 장비 보유 조직: 기존 기관 3곳 + V8 기관·대학·기업 6곳
        assertThat(testType.path("institutions").size()).isEqualTo(9);

        // 12. 기관 담당자(manager1): 대시보드 → 우리 기관 장비 → 장비 48 확인
        JsonNode m1Summary = api.get("/api/v1/dashboard/summary", manager1).data();
        assertThat(m1Summary.path("institution").path("name").asString()).isEqualTo("미리내우주환경시험센터");
        assertThat(api.get("/api/v1/institutions/me/equipment?size=100", manager1).data().toString())
                .contains("ASTRA-CAND-0048");
        assertThat(api.post("/api/v1/equipment/48/confirm", null, manager1).data().path("verificationStatus").asString())
                .isEqualTo("INSTITUTION_VERIFIED");

        // 18. 관리자 대시보드·장비 통계
        JsonNode adminSummary = api.get("/api/v1/dashboard/summary", admin).data();
        assertThat(adminSummary.path("role").asString()).isEqualTo("ADMIN");
        JsonNode stats = api.get("/api/v1/dashboard/equipment-stats", admin).data();
        assertThat(stats.path("total").asLong()).isEqualTo(activeEquipment());
        boolean daejeonVerified = false;
        for (JsonNode i : stats.path("institutions")) {
            if (i.path("id").asLong() == 2) {
                daejeonVerified = i.path("verifiedCount").asLong() >= 1; // 방금 확인한 장비 48
            }
        }
        assertThat(daejeonVerified).isTrue();

    }

    private String token(String email, UserRole role) {
        var row = jdbc.queryForMap("SELECT id, organization_id FROM users WHERE email = ?", email);
        return jwtProvider.createAccessToken(((Number) row.get("id")).longValue(), role,
                row.get("organization_id") == null ? null : ((Number) row.get("organization_id")).longValue());
    }

    private long activeEquipment() {
        return jdbc.queryForObject("SELECT count(*) FROM equipment WHERE is_active", Long.class);
    }

    private static List<Long> ids(JsonNode content) {
        List<Long> ids = new ArrayList<>();
        content.forEach(n -> ids.add(n.path("id").asLong()));
        return ids;
    }
}
