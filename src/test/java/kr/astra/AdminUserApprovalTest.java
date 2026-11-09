package kr.astra;

import static kr.astra.TestApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
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
 * 관리자 회원 관리 (api-spec §12): 회원 목록, 가입 승인·거절
 */
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminUserApprovalTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    CapturingEmailSender mail;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, mail);
    }

    @Test
    void 승인_대기_목록에서_사업자번호를_보고_승인하면_로그인할_수_있다() throws Exception {
        String email = uniqueEmail();
        String company = "테스트 기업 " + UUID.randomUUID();
        long userId = api.signupCompany(email, company, "222-33-44444").data().path("userId").asLong();

        Res list = api.get("/api/v1/admin/users?status=PENDING&role=COMPANY_USER&size=100", adminToken());
        assertThat(list.status()).isEqualTo(200);
        JsonNode item = find(list.data().path("content"), userId);
        assertThat(item).isNotNull();
        assertThat(item.path("email").asString()).isEqualTo(email);
        assertThat(item.path("status").asString()).isEqualTo("PENDING");
        assertThat(item.path("organization").path("name").asString()).isEqualTo(company);
        assertThat(item.path("organization").path("businessRegNo").asString()).isEqualTo("222-33-44444");
        assertThat(item.path("organization").path("isVerified").asBoolean()).isFalse();
        list.data().path("content").forEach(u -> assertThat(u.path("status").asString()).isEqualTo("PENDING"));

        Res approved = api.post("/api/v1/admin/users/" + userId + "/approve", null, adminToken());
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.data().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(approved.data().path("approvedAt").isNull()).isFalse();
        assertThat(approved.data().path("organization").path("isVerified").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT approved_by FROM users WHERE id = ?", Long.class, userId))
                .isEqualTo(adminId());

        assertThat(api.login(email).status()).isEqualTo(200);

        // 이미 승인된 회원은 다시 승인·거절 불가
        Res again = api.post("/api/v1/admin/users/" + userId + "/approve", null, adminToken());
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("USER_INVALID_STATUS");
        assertThat(api.post("/api/v1/admin/users/" + userId + "/reject", "{ \"reason\": \"x\" }", adminToken())
                .code()).isEqualTo("USER_INVALID_STATUS");
    }

    @Test
    void 미등록_기관_담당자를_승인하면_기관도_승인되어_가입용_목록에_나온다() throws Exception {
        String orgName = "테스트 신규 기관 " + UUID.randomUUID();
        long userId = api.signupInstitutionManager(uniqueEmail(), """
                "organization": { "name": "%s", "address": "경남 진주시", "phone": "055-000-0000" }
                """.formatted(orgName)).data().path("userId").asLong();

        assertThat(api.post("/api/v1/admin/users/" + userId + "/approve", null, adminToken()).status())
                .isEqualTo(200);
        assertThat(api.get("/api/v1/auth/institutions", null).data().toString()).contains(orgName);
    }

    @Test
    void 거절하면_로그인할_때_거절_사유가_보인다() throws Exception {
        String email = uniqueEmail();
        long userId = api.signupCompany(email, "테스트 기업 " + UUID.randomUUID(), "333-44-55555")
                .data().path("userId").asLong();

        Res blank = api.post("/api/v1/admin/users/" + userId + "/reject", "{ \"reason\": \" \" }", adminToken());
        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.code()).isEqualTo("VALIDATION_FAILED");

        Res rejected = api.post("/api/v1/admin/users/" + userId + "/reject",
                "{ \"reason\": \"사업자등록번호를 확인할 수 없습니다.\" }", adminToken());
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.data().path("status").asString()).isEqualTo("REJECTED");

        Res login = api.login(email);
        assertThat(login.status()).isEqualTo(403);
        assertThat(login.code()).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat(login.details().path("status").asString()).isEqualTo("REJECTED");
        assertThat(login.details().path("rejectReason").asString()).isEqualTo("사업자등록번호를 확인할 수 없습니다.");
    }

    @Test
    void 거절된_신청의_조직은_같은_이름으로_다시_가입하면_재사용한다() throws Exception {
        String company = "테스트 기업 " + UUID.randomUUID();
        long first = api.signupCompany(uniqueEmail(), company, "444-55-66666").data().path("userId").asLong();

        // 첫 신청이 승인 대기 중이면 같은 이름은 409
        assertThat(api.signupCompany(uniqueEmail(), company, "444-55-66666").code())
                .isEqualTo("ORGANIZATION_ALREADY_EXISTS");

        api.post("/api/v1/admin/users/" + first + "/reject", "{ \"reason\": \"서류 확인 불가\" }", adminToken());
        Res again = api.signupCompany(uniqueEmail(), company, "555-66-77777");
        assertThat(again.status()).isEqualTo(201);
        long second = again.data().path("userId").asLong();

        String sameOrg = "SELECT count(DISTINCT organization_id) FROM users WHERE id IN (?, ?)";
        assertThat(jdbc.queryForObject(sameOrg, Long.class, first, second)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT business_reg_no, is_verified FROM organizations "
                + "WHERE org_type = 'COMPANY' AND name = ?", company))
                .containsEntry("business_reg_no", "555-66-77777").containsEntry("is_verified", false);

        // 승인된 조직은 재사용 대상이 아님
        api.post("/api/v1/admin/users/" + second + "/approve", null, adminToken());
        api.post("/api/v1/admin/users/" + first + "/approve", null, adminToken()); // REJECTED → 409, 상태 변화 없음
        assertThat(api.signupCompany(uniqueEmail(), company, "444-55-66666").code())
                .isEqualTo("ORGANIZATION_ALREADY_EXISTS");
    }

    @Test
    void 정지하면_로그인과_토큰_재발급이_막히고_해제하면_다시_된다() throws Exception {
        String email = uniqueEmail();
        long userId = api.signupCompany(email, "테스트 기업 " + UUID.randomUUID(), "666-77-88888")
                .data().path("userId").asLong();
        // 승인 전(PENDING)에는 정지 불가
        assertThat(api.send("PATCH", "/api/v1/admin/users/" + userId + "/status", "{ \"status\": \"SUSPENDED\" }",
                adminToken()).code()).isEqualTo("USER_INVALID_STATUS");
        api.post("/api/v1/admin/users/" + userId + "/approve", null, adminToken());
        String refresh = api.login(email).data().path("refreshToken").asString();

        Res suspended = api.send("PATCH", "/api/v1/admin/users/" + userId + "/status", "{ \"status\": \"SUSPENDED\" }",
                adminToken());
        assertThat(suspended.status()).isEqualTo(200);
        assertThat(suspended.data().path("status").asString()).isEqualTo("SUSPENDED");
        assertThat(api.login(email).details().path("status").asString()).isEqualTo("SUSPENDED");
        assertThat(api.post("/api/v1/auth/refresh", "{ \"refreshToken\": \"%s\" }".formatted(refresh), null).status())
                .isEqualTo(401);

        assertThat(api.send("PATCH", "/api/v1/admin/users/" + userId + "/status", "{ \"status\": \"ACTIVE\" }",
                adminToken()).data().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(api.login(email).status()).isEqualTo(200);

        // 관리자 계정, 허용 안 된 상태값
        assertThat(api.send("PATCH", "/api/v1/admin/users/" + adminId() + "/status", "{ \"status\": \"SUSPENDED\" }",
                adminToken()).code()).isEqualTo("USER_INVALID_STATUS");
        assertThat(api.send("PATCH", "/api/v1/admin/users/" + userId + "/status", "{ \"status\": \"PENDING\" }",
                adminToken()).code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void 정지하면_이미_받은_access_토큰도_다음_요청부터_바로_막힌다() throws Exception {
        String email = uniqueEmail();
        long userId = api.signupCompany(email, "테스트 기업 " + UUID.randomUUID(), "777-88-99999")
                .data().path("userId").asLong();
        api.post("/api/v1/admin/users/" + userId + "/approve", null, adminToken());
        String access = api.login(email).data().path("accessToken").asString();
        assertThat(api.get("/api/v1/users/me", access).status()).isEqualTo(200); // 상태가 캐시에 들어감

        api.send("PATCH", "/api/v1/admin/users/" + userId + "/status", "{ \"status\": \"SUSPENDED\" }", adminToken());
        Res blocked = api.get("/api/v1/users/me", access);
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.code()).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat(api.get("/api/v1/equipment", access).code()).isEqualTo("ACCOUNT_NOT_ACTIVE");
        // 공개 API(가입용 기관 목록)는 토큰이 붙어 있어도 그대로 쓸 수 있음
        assertThat(api.get("/api/v1/auth/institutions", access).status()).isEqualTo(200);

        api.send("PATCH", "/api/v1/admin/users/" + userId + "/status", "{ \"status\": \"ACTIVE\" }", adminToken());
        assertThat(api.get("/api/v1/users/me", access).status()).isEqualTo(200); // 해제도 바로 반영
    }

    @Test
    void 계정이_없는_토큰은_401() throws Exception {
        String ghost = jwtProvider.createAccessToken(987654321L, UserRole.COMPANY_USER, null);
        Res res = api.get("/api/v1/users/me", ghost);
        assertThat(res.status()).isEqualTo(401);
        assertThat(res.code()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void 관리자가_아니면_403_없는_회원이면_404() throws Exception {
        long companyUserId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'company@astra.demo'", Long.class);
        String companyToken = jwtProvider.createAccessToken(companyUserId, UserRole.COMPANY_USER, null);
        assertThat(api.get("/api/v1/admin/users", companyToken).code()).isEqualTo("FORBIDDEN");
        assertThat(api.post("/api/v1/admin/users/1/approve", null, companyToken).code()).isEqualTo("FORBIDDEN");
        assertThat(api.get("/api/v1/admin/users", null).code()).isEqualTo("AUTH_REQUIRED");

        Res missing = api.post("/api/v1/admin/users/999999/approve", null, adminToken());
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void 회원_목록은_최신_가입순_페이지로_주고_잘못된_정렬_필드는_400() throws Exception {
        Res page = api.get("/api/v1/admin/users?size=2", adminToken());
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.data().path("size").asInt()).isEqualTo(2);
        assertThat(page.data().path("totalElements").asLong()).isGreaterThanOrEqualTo(5);
        JsonNode content = page.data().path("content");
        assertThat(OffsetDateTime.parse(content.get(0).path("createdAt").asString()))
                .isAfterOrEqualTo(OffsetDateTime.parse(content.get(1).path("createdAt").asString()));

        assertThat(api.get("/api/v1/admin/users?sort=nope,desc", adminToken()).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(api.get("/api/v1/admin/users?status=WHATEVER", adminToken()).code()).isEqualTo("VALIDATION_FAILED");
    }

    private long adminId() {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = 'admin@astra.demo'", Long.class);
    }

    private String adminToken() {
        return jwtProvider.createAccessToken(adminId(), UserRole.ADMIN, null);
    }

    private static JsonNode find(JsonNode content, long userId) {
        for (JsonNode n : content) {
            if (n.path("id").asLong() == userId) {
                return n;
            }
        }
        return null;
    }
}
