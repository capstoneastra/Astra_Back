package kr.astra;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 회원가입 → 로그인 → 내 정보 → 토큰 재발급 → 로그아웃 (api-spec §1) 과 V3 시연 계정 seed 확인.
 * 테스트 비밀번호는 이 테스트에서 가입시킨 계정 전용입니다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowTest {

    private static final String PASSWORD = "test-password-1";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = JsonMapper.builder().build();

    // ------------------------------------------------------------ 기업 가입 → 로그인 → 내 정보 → 재발급 → 로그아웃

    @Test
    void 기업_회원은_가입_직후_로그인하고_내_정보를_수정할_수_있다() throws Exception {
        String email = uniqueEmail();
        String company = "테스트 기업 " + UUID.randomUUID();
        Res signup = post("/api/v1/auth/signup", """
                { "email": "%s", "password": "%s", "name": "홍길동", "phone": "010-1111-2222",
                  "role": "COMPANY_USER", "organization": { "name": "%s", "businessRegNo": "111-22-33333" } }
                """.formatted(email, PASSWORD, company), null);
        assertThat(signup.status).isEqualTo(201);
        assertThat(signup.data().path("status").asString()).isEqualTo("ACTIVE");

        Res login = login(email);
        assertThat(login.status).isEqualTo(200);
        JsonNode tokens = login.data();
        assertThat(tokens.path("expiresIn").asLong()).isEqualTo(3600);
        assertThat(tokens.path("user").path("role").asString()).isEqualTo("COMPANY_USER");
        String access = tokens.path("accessToken").asString();
        String refresh = tokens.path("refreshToken").asString();

        Res me = get("/api/v1/users/me", access);
        assertThat(me.status).isEqualTo(200);
        assertThat(me.data().path("email").asString()).isEqualTo(email);
        assertThat(me.data().path("organization").path("name").asString()).isEqualTo(company);
        assertThat(me.data().path("organization").path("orgType").asString()).isEqualTo("COMPANY");
        assertThat(me.data().path("lastLoginAt").isNull()).isFalse();

        Res patched = send("PATCH", "/api/v1/users/me", "{ \"phone\": \"010-9999-8888\" }", access);
        assertThat(patched.status).isEqualTo(200);
        assertThat(patched.data().path("phone").asString()).isEqualTo("010-9999-8888");
        assertThat(patched.data().path("name").asString()).isEqualTo("홍길동");

        // 재발급: 새 토큰을 받고, 쓴 refresh 토큰은 다시 쓸 수 없음
        Res refreshed = post("/api/v1/auth/refresh", refreshBody(refresh), null);
        assertThat(refreshed.status).isEqualTo(200);
        String newRefresh = refreshed.data().path("refreshToken").asString();
        assertThat(newRefresh).isNotEqualTo(refresh);
        assertThat(post("/api/v1/auth/refresh", refreshBody(refresh), null).code()).isEqualTo("AUTH_REQUIRED");

        // 로그아웃: 로그인 필요, 이후 그 refresh 토큰은 폐기
        assertThat(post("/api/v1/auth/logout", refreshBody(newRefresh), null).code()).isEqualTo("AUTH_REQUIRED");
        assertThat(post("/api/v1/auth/logout", refreshBody(newRefresh), access).status).isEqualTo(200);
        assertThat(post("/api/v1/auth/refresh", refreshBody(newRefresh), null).code()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void 비밀번호가_틀리면_401_INVALID_CREDENTIALS() throws Exception {
        String email = signupCompany();
        Res res = post("/api/v1/auth/login",
                "{ \"email\": \"%s\", \"password\": \"wrong-password\" }".formatted(email), null);
        assertThat(res.status).isEqualTo(401);
        assertThat(res.code()).isEqualTo("INVALID_CREDENTIALS");

        Res unknown = post("/api/v1/auth/login",
                "{ \"email\": \"nobody@test.kr\", \"password\": \"wrong-password\" }", null);
        assertThat(unknown.code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void 이미_가입된_이메일은_409_EMAIL_ALREADY_EXISTS() throws Exception {
        String email = signupCompany();
        Res res = post("/api/v1/auth/signup", companySignup(email.toUpperCase(), "다른 기업 " + UUID.randomUUID()), null);
        assertThat(res.status).isEqualTo(409);
        assertThat(res.code()).isEqualTo("EMAIL_ALREADY_EXISTS");
    }

    @Test
    void 같은_이름의_기업은_409_ORGANIZATION_ALREADY_EXISTS() throws Exception {
        Res res = post("/api/v1/auth/signup", companySignup(uniqueEmail(), "가상 A기업"), null);
        assertThat(res.status).isEqualTo(409);
        assertThat(res.code()).isEqualTo("ORGANIZATION_ALREADY_EXISTS");
    }

    @Test
    void 관리자_역할로는_가입할_수_없다() throws Exception {
        Res res = post("/api/v1/auth/signup", """
                { "email": "%s", "password": "%s", "name": "관리자", "role": "ADMIN" }
                """.formatted(uniqueEmail(), PASSWORD), null);
        assertThat(res.status).isEqualTo(400);
        assertThat(res.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(res.body.path("error").path("details").path("fields").get(0).path("field").asString())
                .isEqualTo("role");
    }

    // ------------------------------------------------------------ 기관 담당자 가입

    @Test
    void 기관_담당자는_승인_전까지_PENDING_이고_로그인하면_403() throws Exception {
        long institutionId = institutionId("한국재료연구원");
        String email = uniqueEmail();
        Res signup = post("/api/v1/auth/signup", """
                { "email": "%s", "password": "%s", "name": "김담당", "role": "INSTITUTION_MANAGER",
                  "organizationId": %d }
                """.formatted(email, PASSWORD, institutionId), null);
        assertThat(signup.status).isEqualTo(201);
        assertThat(signup.data().path("status").asString()).isEqualTo("PENDING");

        Res login = login(email);
        assertThat(login.status).isEqualTo(403);
        assertThat(login.code()).isEqualTo("ACCOUNT_NOT_ACTIVE");
    }

    @Test
    void 미등록_기관으로_가입하면_승인_대기_기관이_생기고_가입용_목록에는_안_나온다() throws Exception {
        String orgName = "테스트 신규 기관 " + UUID.randomUUID();
        Res signup = post("/api/v1/auth/signup", """
                { "email": "%s", "password": "%s", "name": "이담당", "role": "INSTITUTION_MANAGER",
                  "organization": { "name": "%s", "address": "경남 진주시", "phone": "055-000-0000" } }
                """.formatted(uniqueEmail(), PASSWORD, orgName), null);
        assertThat(signup.status).isEqualTo(201);
        assertThat(signup.data().path("status").asString()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT is_verified FROM organizations WHERE org_type = 'INSTITUTION' AND name = ?",
                Boolean.class, orgName)).isFalse();

        Res list = get("/api/v1/auth/institutions", null);
        assertThat(list.status).isEqualTo(200);
        assertThat(list.data().toString()).doesNotContain(orgName);
    }

    @Test
    void 승인되지_않은_기관이나_기업_조직은_선택할_수_없다() throws Exception {
        long companyOrgId = jdbc.queryForObject(
                "SELECT id FROM organizations WHERE org_type = 'COMPANY' AND name = '가상 A기업'", Long.class);
        Res res = post("/api/v1/auth/signup", """
                { "email": "%s", "password": "%s", "name": "김담당", "role": "INSTITUTION_MANAGER",
                  "organizationId": %d }
                """.formatted(uniqueEmail(), PASSWORD, companyOrgId), null);
        assertThat(res.status).isEqualTo(400);
        assertThat(res.code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void 가입용_기관_목록은_로그인_없이_id_이름_지역만_준다() throws Exception {
        Res res = get("/api/v1/auth/institutions", null);
        assertThat(res.status).isEqualTo(200);
        JsonNode first = res.data().get(0);
        assertThat(first.propertyNames()).containsExactlyInAnyOrder("id", "name", "region");
        assertThat(res.data().size()).isGreaterThanOrEqualTo(9);
    }

    // ------------------------------------------------------------ V3 시연 계정

    @Test
    void 시연_계정과_기관_승인이_seed_로_들어간다() {
        // V2 로 들어간 기관(장비 보유 기관)은 모두 승인 상태
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organizations o WHERE NOT o.is_verified "
                + "AND EXISTS (SELECT 1 FROM equipment e WHERE e.institution_id = o.id)", Long.class)).isZero();
        assertThat(jdbc.queryForList(
                "SELECT role || ':' || status FROM users WHERE email LIKE '%@astra.demo' ORDER BY email", String.class))
                .containsExactly("ADMIN:ACTIVE", "COMPANY_USER:ACTIVE", "INSTITUTION_MANAGER:ACTIVE",
                        "INSTITUTION_MANAGER:ACTIVE", "INSTITUTION_MANAGER:PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email LIKE '%@astra.demo' "
                + "AND password_hash LIKE '$2a$10$%'", Long.class)).isEqualTo(5);
    }

    // ------------------------------------------------------------ helpers

    private String signupCompany() throws Exception {
        String email = uniqueEmail();
        Res res = post("/api/v1/auth/signup", companySignup(email, "테스트 기업 " + UUID.randomUUID()), null);
        assertThat(res.status).isEqualTo(201);
        return email;
    }

    private static String companySignup(String email, String companyName) {
        return """
                { "email": "%s", "password": "%s", "name": "홍길동", "role": "COMPANY_USER",
                  "organization": { "name": "%s", "businessRegNo": "111-22-33333" } }
                """.formatted(email, PASSWORD, companyName);
    }

    private Res login(String email) throws Exception {
        return post("/api/v1/auth/login",
                "{ \"email\": \"%s\", \"password\": \"%s\" }".formatted(email, PASSWORD), null);
    }

    private long institutionId(String name) {
        return jdbc.queryForObject("SELECT id FROM organizations WHERE org_type = 'INSTITUTION' AND name = ?",
                Long.class, name);
    }

    private static String refreshBody(String token) {
        return "{ \"refreshToken\": \"%s\" }".formatted(token);
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@test.kr";
    }

    private Res get(String path, String accessToken) throws Exception {
        return send("GET", path, null, accessToken);
    }

    private Res post(String path, String body, String accessToken) throws Exception {
        return send("POST", path, body, accessToken);
    }

    private Res send(String method, String path, String body, String accessToken) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (accessToken != null) {
            req.header("Authorization", "Bearer " + accessToken);
        }
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(res.statusCode(), json.readTree(res.body()));
    }

    private record Res(int status, JsonNode body) {
        JsonNode data() {
            return body.path("data");
        }

        String code() {
            return body.path("error").path("code").asString();
        }
    }
}
