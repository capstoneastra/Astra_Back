package kr.astra;

import static kr.astra.TestApi.PASSWORD;
import static kr.astra.TestApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * 이메일 인증 → 회원가입(PENDING) → 관리자 승인 → 로그인 → 내 정보 → 토큰 재발급 → 로그아웃 (api-spec §1)
 * 과 V3 시연 계정 seed 확인.
 */
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowTest {

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

    // ------------------------------------------------------------ 기업 가입 → 승인 → 로그인 → 내 정보 → 재발급 → 로그아웃

    @Test
    void 기업_회원은_관리자_승인_후_로그인하고_내_정보를_수정할_수_있다() throws Exception {
        String email = uniqueEmail();
        String company = "테스트 기업 " + UUID.randomUUID();
        Res signup = api.signupCompany(email, company, "1112233333");
        assertThat(signup.status()).isEqualTo(201);
        assertThat(signup.data().path("status").asString()).isEqualTo("PENDING");
        long userId = signup.data().path("userId").asLong();
        // 사업자등록번호는 하이픈 형식으로 저장, 기업 조직은 승인 전까지 미승인
        assertThat(jdbc.queryForMap("SELECT business_reg_no, is_verified FROM organizations "
                + "WHERE org_type = 'COMPANY' AND name = ?", company))
                .containsEntry("business_reg_no", "111-22-33333").containsEntry("is_verified", false);

        // 승인 전 로그인: 403 + details.status
        Res pending = api.login(email);
        assertThat(pending.status()).isEqualTo(403);
        assertThat(pending.code()).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat(pending.details().path("status").asString()).isEqualTo("PENDING");

        assertThat(api.post("/api/v1/admin/users/" + userId + "/approve", null, adminToken()).status()).isEqualTo(200);

        Res login = api.login(email);
        assertThat(login.status()).isEqualTo(200);
        JsonNode tokens = login.data();
        assertThat(tokens.path("expiresIn").asLong()).isEqualTo(3600);
        assertThat(tokens.path("user").path("role").asString()).isEqualTo("COMPANY_USER");
        String access = tokens.path("accessToken").asString();
        String refresh = tokens.path("refreshToken").asString();

        Res me = api.get("/api/v1/users/me", access);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.data().path("email").asString()).isEqualTo(email);
        assertThat(me.data().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(me.data().path("organization").path("name").asString()).isEqualTo(company);
        assertThat(me.data().path("organization").path("orgType").asString()).isEqualTo("COMPANY");
        assertThat(me.data().path("organization").path("isVerified").asBoolean()).isTrue();
        assertThat(me.data().path("lastLoginAt").isNull()).isFalse();

        Res patched = api.send("PATCH", "/api/v1/users/me", "{ \"phone\": \"010-9999-8888\" }", access);
        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.data().path("phone").asString()).isEqualTo("010-9999-8888");
        assertThat(patched.data().path("name").asString()).isEqualTo("홍길동");

        // 재발급: 새 토큰을 받고, 쓴 refresh 토큰은 다시 쓸 수 없음
        Res refreshed = api.post("/api/v1/auth/refresh", refreshBody(refresh), null);
        assertThat(refreshed.status()).isEqualTo(200);
        String newRefresh = refreshed.data().path("refreshToken").asString();
        assertThat(newRefresh).isNotEqualTo(refresh);
        assertThat(api.post("/api/v1/auth/refresh", refreshBody(refresh), null).code()).isEqualTo("AUTH_REQUIRED");

        // 로그아웃: 로그인 필요, 이후 그 refresh 토큰은 폐기
        assertThat(api.post("/api/v1/auth/logout", refreshBody(newRefresh), null).code()).isEqualTo("AUTH_REQUIRED");
        assertThat(api.post("/api/v1/auth/logout", refreshBody(newRefresh), access).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/auth/refresh", refreshBody(newRefresh), null).code()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void 비밀번호가_틀리면_401_INVALID_CREDENTIALS() throws Exception {
        String email = uniqueEmail();
        assertThat(api.signupCompany(email, "테스트 기업 " + UUID.randomUUID(), "111-22-33333").status())
                .isEqualTo(201);
        Res res = api.post("/api/v1/auth/login",
                "{ \"email\": \"%s\", \"password\": \"wrong-password\" }".formatted(email), null);
        assertThat(res.status()).isEqualTo(401);
        assertThat(res.code()).isEqualTo("INVALID_CREDENTIALS");

        Res unknown = api.post("/api/v1/auth/login",
                "{ \"email\": \"nobody@test.kr\", \"password\": \"wrong-password\" }", null);
        assertThat(unknown.code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void 시연_계정은_V9_비밀번호로_로그인되고_승인_대기_계정은_403() throws Exception {
        for (String email : new String[] {"admin@astra.demo", "company@astra.demo", "company2@astra.demo",
                "manager1@astra.demo", "manager2@astra.demo", "univ1@astra.demo"}) {
            Res login = api.post("/api/v1/auth/login",
                    "{ \"email\": \"%s\", \"password\": \"12345678\" }".formatted(email), null);
            assertThat(login.status()).as(email + " " + login.body()).isEqualTo(200);
        }
        Res pending = api.post("/api/v1/auth/login",
                "{ \"email\": \"pending@astra.demo\", \"password\": \"12345678\" }", null);
        assertThat(pending.code()).isEqualTo("ACCOUNT_NOT_ACTIVE");
    }

    @Test
    void 이미_가입된_이메일은_인증_코드도_보내지_않고_409_EMAIL_ALREADY_EXISTS() throws Exception {
        Res res = api.post("/api/v1/auth/email-verifications", "{ \"email\": \"COMPANY@astra.demo\" }", null);
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.code()).isEqualTo("EMAIL_ALREADY_EXISTS");
    }

    @Test
    void 같은_이름의_기업은_409_ORGANIZATION_ALREADY_EXISTS() throws Exception {
        Res res = api.signupCompany(uniqueEmail(), "가상 A기업", "111-22-33333");
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.code()).isEqualTo("ORGANIZATION_ALREADY_EXISTS");
    }

    @Test
    void 사업자등록번호는_숫자_10자리여야_한다() throws Exception {
        Res res = api.signupCompany(uniqueEmail(), "테스트 기업 " + UUID.randomUUID(), "12-345");
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(res.details().path("fields").get(0).path("field").asString())
                .isEqualTo("organization.businessRegNo");
    }

    @Test
    void 관리자_역할로는_가입할_수_없다() throws Exception {
        Res res = api.post("/api/v1/auth/signup", """
                { "email": "%s", "password": "%s", "name": "관리자", "role": "ADMIN", "verificationToken": "x" }
                """.formatted(uniqueEmail(), PASSWORD), null);
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(res.details().path("fields").get(0).path("field").asString()).isEqualTo("role");
    }

    // ------------------------------------------------------------ 이메일 인증

    @Test
    void 이메일_인증_없이는_가입할_수_없다() throws Exception {
        String email = uniqueEmail();
        Res noToken = api.post("/api/v1/auth/signup", companyBody(email, "", "테스트 기업 " + UUID.randomUUID()), null);
        assertThat(noToken.code()).isEqualTo("VALIDATION_FAILED");

        Res fakeToken = api.post("/api/v1/auth/signup",
                companyBody(email, "not-a-real-token", "테스트 기업 " + UUID.randomUUID()), null);
        assertThat(fakeToken.status()).isEqualTo(400);
        assertThat(fakeToken.code()).isEqualTo("EMAIL_NOT_VERIFIED");
    }

    @Test
    void 인증_토큰은_그_이메일로_한_번만_쓸_수_있다() throws Exception {
        String email = uniqueEmail();
        String token = api.verificationToken(email);

        // 다른 이메일로 가입 시도 → 거절, 토큰은 그대로 남음
        Res otherEmail = api.post("/api/v1/auth/signup",
                companyBody(uniqueEmail(), token, "테스트 기업 " + UUID.randomUUID()), null);
        assertThat(otherEmail.code()).isEqualTo("EMAIL_NOT_VERIFIED");

        // 가입이 다른 이유(조직명 중복)로 실패하면 토큰 사용도 되돌아감
        assertThat(api.post("/api/v1/auth/signup", companyBody(email, token, "가상 A기업"), null).code())
                .isEqualTo("ORGANIZATION_ALREADY_EXISTS");

        assertThat(api.post("/api/v1/auth/signup",
                companyBody(email, token, "테스트 기업 " + UUID.randomUUID()), null).status()).isEqualTo(201);
        // 같은 토큰으로 다시 가입 → 이미 가입된 이메일
        assertThat(api.post("/api/v1/auth/signup",
                companyBody(email, token, "테스트 기업 " + UUID.randomUUID()), null).code())
                .isEqualTo("EMAIL_ALREADY_EXISTS");
        assertThat(jdbc.queryForObject("SELECT used_at IS NOT NULL FROM email_verifications WHERE email = ?",
                Boolean.class, email)).isTrue();
    }

    @Test
    void 코드가_틀리면_남은_횟수를_알려주고_5번_틀리면_다시_받아야_한다() throws Exception {
        String email = uniqueEmail();
        assertThat(api.post("/api/v1/auth/email-verifications", emailBody(email), null).status()).isEqualTo(200);
        String right = mail.lastCode(email);
        String wrong = right.equals("000000") ? "111111" : "000000";

        Res first = confirm(email, wrong);
        assertThat(first.status()).isEqualTo(400);
        assertThat(first.code()).isEqualTo("VERIFICATION_CODE_INVALID");
        assertThat(first.details().path("remainingAttempts").asInt()).isEqualTo(4);
        for (int i = 0; i < 4; i++) {
            confirm(email, wrong);
        }
        // 5번 틀린 뒤에는 맞는 코드도 거절
        assertThat(confirm(email, right).code()).isEqualTo("VERIFICATION_CODE_EXPIRED");
    }

    @Test
    void 만료된_코드는_400_VERIFICATION_CODE_EXPIRED() throws Exception {
        String email = uniqueEmail();
        api.post("/api/v1/auth/email-verifications", emailBody(email), null);
        jdbc.update("UPDATE email_verifications SET code_expires_at = now() - interval '1 second' WHERE email = ?",
                email);
        assertThat(confirm(email, mail.lastCode(email)).code()).isEqualTo("VERIFICATION_CODE_EXPIRED");
        // 보낸 적 없는 이메일도 같은 코드
        assertThat(confirm(uniqueEmail(), "123456").code()).isEqualTo("VERIFICATION_CODE_EXPIRED");
    }

    @Test
    void 인증_코드는_60초에_한_번만_보낼_수_있다() throws Exception {
        String email = uniqueEmail();
        assertThat(api.post("/api/v1/auth/email-verifications", emailBody(email), null).data()
                .path("expiresIn").asLong()).isEqualTo(600);
        Res again = api.post("/api/v1/auth/email-verifications", emailBody(email), null);
        assertThat(again.status()).isEqualTo(429);
        assertThat(again.code()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(again.details().path("retryAfter").asLong()).isBetween(1L, 60L);
        // DB 에는 코드 원문이 아니라 해시만 저장
        assertThat(jdbc.queryForObject("SELECT code_hash FROM email_verifications WHERE email = ?",
                String.class, email)).hasSize(64).isNotEqualTo(mail.lastCode(email));
    }

    // ------------------------------------------------------------ 기관 담당자 가입

    @Test
    void 기관_담당자는_승인_전까지_PENDING_이고_로그인하면_403() throws Exception {
        String email = uniqueEmail();
        Res signup = api.signupInstitutionManager(email, "\"organizationId\": " + institutionId("가람재료시험연구원"));
        assertThat(signup.status()).isEqualTo(201);
        assertThat(signup.data().path("status").asString()).isEqualTo("PENDING");

        Res login = api.login(email);
        assertThat(login.status()).isEqualTo(403);
        assertThat(login.code()).isEqualTo("ACCOUNT_NOT_ACTIVE");
    }

    @Test
    void 미등록_기관으로_가입하면_승인_대기_기관이_생기고_가입용_목록에는_안_나온다() throws Exception {
        String orgName = "테스트 신규 기관 " + UUID.randomUUID();
        Res signup = api.signupInstitutionManager(uniqueEmail(), """
                "organization": { "name": "%s", "address": "경남 진주시", "phone": "055-000-0000" }
                """.formatted(orgName));
        assertThat(signup.status()).isEqualTo(201);
        assertThat(signup.data().path("status").asString()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT is_verified FROM organizations WHERE org_type = 'INSTITUTION' AND name = ?",
                Boolean.class, orgName)).isFalse();

        Res list = api.get("/api/v1/auth/institutions", null);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.data().toString()).doesNotContain(orgName);
    }

    @Test
    void 대학으로_신청하면_UNIVERSITY_조직이_생기고_승인_후_가입용_목록에_나온다() throws Exception {
        String orgName = "테스트 신규 대학 " + UUID.randomUUID();
        String email = uniqueEmail();
        Res signup = api.signupInstitutionManager(email, """
                "organization": { "orgType": "UNIVERSITY", "name": "%s", "address": "경남 진주시",
                                  "phone": "055-000-0001" }
                """.formatted(orgName));
        assertThat(signup.status()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                "SELECT is_verified FROM organizations WHERE org_type = 'UNIVERSITY' AND name = ?",
                Boolean.class, orgName)).isFalse();
        assertThat(api.get("/api/v1/auth/institutions", null).data().toString()).doesNotContain(orgName);

        long userId = signup.data().path("userId").asLong();
        assertThat(api.post("/api/v1/admin/users/" + userId + "/approve", null, adminToken()).status())
                .isEqualTo(200);
        JsonNode listed = null;
        for (JsonNode n : api.get("/api/v1/auth/institutions", null).data()) {
            if (orgName.equals(n.path("name").asString())) {
                listed = n;
            }
        }
        assertThat(listed).isNotNull();
        assertThat(listed.path("orgType").asString()).isEqualTo("UNIVERSITY");

        Res me = api.get("/api/v1/users/me", api.login(email).data().path("accessToken").asString());
        assertThat(me.data().path("organization").path("orgType").asString()).isEqualTo("UNIVERSITY");
    }

    @Test
    void 기관_담당자_신청에_기업_유형은_쓸_수_없다() throws Exception {
        Res res = api.signupInstitutionManager(uniqueEmail(), """
                "organization": { "orgType": "COMPANY", "name": "테스트 유형 오류 %s" }
                """.formatted(UUID.randomUUID()));
        assertThat(res.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(res.details().path("fields").get(0).path("field").asString()).isEqualTo("organization.orgType");
    }

    @Test
    void 승인되지_않은_기관이나_기업_조직은_선택할_수_없다() throws Exception {
        long companyOrgId = jdbc.queryForObject(
                "SELECT id FROM organizations WHERE org_type = 'COMPANY' AND name = '가상 A기업'", Long.class);
        Res res = api.signupInstitutionManager(uniqueEmail(), "\"organizationId\": " + companyOrgId);
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void 가입용_기관_목록은_로그인_없이_id_유형_이름_지역만_준다() throws Exception {
        Res res = api.get("/api/v1/auth/institutions", null);
        assertThat(res.status()).isEqualTo(200);
        JsonNode first = res.data().get(0);
        assertThat(first.propertyNames()).containsExactlyInAnyOrder("id", "orgType", "name", "region");
        assertThat(res.data().size()).isGreaterThanOrEqualTo(9);
        // 가나다순: 가람재료시험연구원 … 한빛뿌리기술지원센터 (기관·대학)
        List<String> names = new ArrayList<>();
        res.data().forEach(n -> names.add(n.path("name").asString()));
        assertThat(names).isSortedAccordingTo(Collator.getInstance(Locale.KOREAN));
        assertThat(names.getFirst()).isEqualTo("가람재료시험연구원");
        assertThat(names).contains("가온대학교 첨단소재분석센터", "늘해랑대학교 항공우주공동기기원")
                .doesNotContain("미르항공정밀"); // 기업은 가입용 기관 목록에 없음
    }

    // ------------------------------------------------------------ V3 시연 계정

    @Test
    void 시연_계정과_기관_승인이_seed_로_들어간다() {
        // 장비를 가진 조직(V2 기관, V8 기관·대학·기업)은 모두 승인 상태
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organizations o WHERE NOT o.is_verified "
                + "AND EXISTS (SELECT 1 FROM equipment e WHERE e.institution_id = o.id)", Long.class)).isZero();
        assertThat(jdbc.queryForList(
                "SELECT role || ':' || status FROM users WHERE email LIKE '%@astra.demo' ORDER BY email", String.class))
                // admin, company, company2(V8 장비 보유 기업), manager1, manager2, pending, univ1(V8 대학)
                .containsExactly("ADMIN:ACTIVE", "COMPANY_USER:ACTIVE", "COMPANY_USER:ACTIVE",
                        "INSTITUTION_MANAGER:ACTIVE", "INSTITUTION_MANAGER:ACTIVE", "INSTITUTION_MANAGER:PENDING",
                        "INSTITUTION_MANAGER:ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email LIKE '%@astra.demo' "
                + "AND password_hash LIKE '$2a$10$%'", Long.class)).isEqualTo(7);
    }

    // ------------------------------------------------------------ helpers

    private String adminToken() {
        long adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'admin@astra.demo'", Long.class);
        return jwtProvider.createAccessToken(adminId, UserRole.ADMIN, null);
    }

    private Res confirm(String email, String code) throws Exception {
        return api.post("/api/v1/auth/email-verifications/confirm",
                "{ \"email\": \"%s\", \"code\": \"%s\" }".formatted(email, code), null);
    }

    private static String emailBody(String email) {
        return "{ \"email\": \"%s\" }".formatted(email);
    }

    private static String companyBody(String email, String token, String companyName) {
        return """
                { "email": "%s", "password": "%s", "name": "홍길동", "role": "COMPANY_USER",
                  "verificationToken": "%s",
                  "organization": { "name": "%s", "businessRegNo": "111-22-33333" } }
                """.formatted(email, PASSWORD, token, companyName);
    }

    private long institutionId(String name) {
        return jdbc.queryForObject("SELECT id FROM organizations WHERE org_type = 'INSTITUTION' AND name = ?",
                Long.class, name);
    }

    private static String refreshBody(String token) {
        return "{ \"refreshToken\": \"%s\" }".formatted(token);
    }
}
