package kr.astra;

import static kr.astra.TestApi.PASSWORD;
import static kr.astra.TestApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

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

/** 계정 찾기: 이메일 찾기, 비밀번호 재설정 (api-spec §1-2) */
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountRecoveryTest {

    private static final String NEW_PASSWORD = "new-pass-1234";

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
    void 이름과_연락처가_둘_다_맞으면_가려진_이메일을_돌려준다() throws Exception {
        String email = "co" + UUID.randomUUID().toString().substring(0, 8) + "@test.kr";
        String name = "찾기" + UUID.randomUUID().toString().substring(0, 6);
        assertThat(api.signupCompany(email, "찾기 기업 " + UUID.randomUUID(), "1112233333").status()).isEqualTo(201);
        jdbc.update("UPDATE users SET name = ?, phone = '010-1234-5678' WHERE email = ?", name, email);

        // 연락처는 숫자만 비교 (승인 대기 계정도 찾을 수 있음)
        Res found = findEmail(name, "01012345678");
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.data().path("emails")).hasSize(1);
        String masked = found.data().path("emails").get(0).asString();
        assertThat(masked).isEqualTo("co" + "*".repeat(email.indexOf('@') - 2) + "@test.kr");

        // 하나라도 다르면 빈 배열 (항상 200)
        assertThat(findEmail(name, "010-9999-9999").data().path("emails")).isEmpty();
        assertThat(findEmail(name + "x", "010-1234-5678").data().path("emails")).isEmpty();

        // 빈 값은 400
        Res blank = findEmail("", "010-1234-5678");
        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void 코드_확인_후_새_비밀번호로_바꾸면_이전_refresh_토큰은_폐기된다() throws Exception {
        String email = uniqueEmail();
        long userId = api.signupCompany(email, "재설정 기업 " + UUID.randomUUID(), "1112233333")
                .data().path("userId").asLong();
        assertThat(api.post("/api/v1/admin/users/" + userId + "/approve", null, adminToken()).status()).isEqualTo(200);
        String oldRefresh = api.login(email).data().path("refreshToken").asString();

        Res sent = sendReset(email);
        assertThat(sent.status()).isEqualTo(200);
        assertThat(sent.data().path("expiresIn").asLong()).isEqualTo(600);
        assertThat(sent.data().path("resendAvailableIn").asLong()).isEqualTo(60);

        // 60초 안에 다시 보내면 429
        Res again = sendReset(email);
        assertThat(again.status()).isEqualTo(429);
        assertThat(again.code()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(again.details().path("retryAfter").asLong()).isPositive();

        // 틀린 코드 → 남은 횟수
        String code = mail.lastCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";
        Res invalid = confirm(email, wrong);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.code()).isEqualTo("VERIFICATION_CODE_INVALID");
        assertThat(invalid.details().path("remainingAttempts").asInt()).isEqualTo(4);

        Res confirmed = confirm(email, code);
        assertThat(confirmed.status()).as(confirmed.body().toString()).isEqualTo(200);
        assertThat(confirmed.data().path("expiresIn").asLong()).isEqualTo(900);
        String resetToken = confirmed.data().path("resetToken").asString();
        assertThat(resetToken).isNotBlank();

        // 한 번 확인한 코드는 다시 못 씀
        assertThat(confirm(email, code).code()).isEqualTo("VERIFICATION_CODE_EXPIRED");

        // 비밀번호 규칙은 회원가입과 같음
        Res tooShort = complete(resetToken, "short");
        assertThat(tooShort.status()).isEqualTo(400);
        assertThat(tooShort.code()).isEqualTo("VALIDATION_FAILED");

        Res done = complete(resetToken, NEW_PASSWORD);
        assertThat(done.status()).as(done.body().toString()).isEqualTo(200);
        assertThat(done.body().path("success").asBoolean()).isTrue();
        assertThat(done.body().has("data")).isFalse();

        // 토큰은 한 번만
        Res reused = complete(resetToken, "another-pass-1234");
        assertThat(reused.status()).isEqualTo(400);
        assertThat(reused.code()).isEqualTo("RESET_TOKEN_INVALID");
        assertThat(complete("no-such-token", NEW_PASSWORD).code()).isEqualTo("RESET_TOKEN_INVALID");

        // 이전 비밀번호 401, 새 비밀번호 200, 이전 refresh 토큰 401
        assertThat(login(email, PASSWORD).status()).isEqualTo(401);
        assertThat(login(email, NEW_PASSWORD).status()).isEqualTo(200);
        Res refreshed = api.post("/api/v1/auth/refresh", "{ \"refreshToken\": \"%s\" }".formatted(oldRefresh), null);
        assertThat(refreshed.status()).isEqualTo(401);
    }

    @Test
    void 가입하지_않은_이메일도_같은_응답이고_메일은_보내지_않는다() throws Exception {
        String email = uniqueEmail();
        Res sent = sendReset(email);
        assertThat(sent.status()).isEqualTo(200);
        assertThat(sent.data().path("expiresIn").asLong()).isEqualTo(600);
        assertThat(mail.lastCode(email)).isNull();
        // 60초 제한도 똑같이 걸림 (계정이 있는지 드러나지 않게)
        assertThat(sendReset(email).status()).isEqualTo(429);
        assertThat(confirm(email, "123456").code()).isIn("VERIFICATION_CODE_INVALID");
    }

    @Test
    void 코드를_5번_틀리면_다시_받아야_한다() throws Exception {
        String email = uniqueEmail();
        api.signupCompany(email, "틀림 기업 " + UUID.randomUUID(), "1112233333");
        sendReset(email);
        String code = mail.lastCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 4; i >= 0; i--) {
            assertThat(confirm(email, wrong).details().path("remainingAttempts").asInt()).isEqualTo(i);
        }
        assertThat(confirm(email, code).code()).isEqualTo("VERIFICATION_CODE_EXPIRED");
    }

    private Res findEmail(String name, String phone) throws Exception {
        return api.post("/api/v1/auth/find-email",
                "{ \"name\": \"%s\", \"phone\": \"%s\" }".formatted(name, phone), null);
    }

    private Res sendReset(String email) throws Exception {
        return api.post("/api/v1/auth/password-resets", "{ \"email\": \"%s\" }".formatted(email), null);
    }

    private Res confirm(String email, String code) throws Exception {
        return api.post("/api/v1/auth/password-resets/confirm",
                "{ \"email\": \"%s\", \"code\": \"%s\" }".formatted(email, code), null);
    }

    private Res complete(String token, String password) throws Exception {
        return api.post("/api/v1/auth/password-resets/complete",
                "{ \"resetToken\": \"%s\", \"newPassword\": \"%s\" }".formatted(token, password), null);
    }

    private Res login(String email, String password) throws Exception {
        return api.post("/api/v1/auth/login",
                "{ \"email\": \"%s\", \"password\": \"%s\" }".formatted(email, password), null);
    }

    private String adminToken() {
        long adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'admin@astra.kr'", Long.class);
        return jwtProvider.createAccessToken(adminId, UserRole.ADMIN, null);
    }
}
