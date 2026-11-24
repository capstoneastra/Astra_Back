package kr.astra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import kr.astra.TestApi.Res;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** PATCH /users/me/password (api-spec §1). 시연 계정 비밀번호를 바꾸지 않도록 테스트마다 새 회원을 만듭니다. */
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserPasswordTest {

    private static final String NEW_PASSWORD = "new-password-2";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private TestApi api;
    private String email;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(port, null);
        email = "pw-" + UUID.randomUUID() + "@test.local";
        long orgId = jdbc.queryForObject("INSERT INTO organizations (org_type, name) VALUES ('COMPANY', ?) RETURNING id",
                Long.class, "비밀번호 기업 " + UUID.randomUUID());
        jdbc.update("""
                INSERT INTO users (organization_id, email, password_hash, name, role, status)
                VALUES (?, ?, ?, '테스트', 'COMPANY_USER', 'ACTIVE')
                """, orgId, email, passwordEncoder.encode(TestApi.PASSWORD));
        token = api.login(email).data().path("accessToken").asString();
    }

    @Test
    void 현재_비밀번호가_맞으면_바뀌고_새_비밀번호로_로그인된다() throws Exception {
        Res res = change(TestApi.PASSWORD, NEW_PASSWORD);
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().path("success").asBoolean()).isTrue();

        assertThat(api.login(email).code()).isEqualTo("INVALID_CREDENTIALS");
        Res login = api.post("/api/v1/auth/login",
                "{ \"email\": \"%s\", \"password\": \"%s\" }".formatted(email, NEW_PASSWORD), null);
        assertThat(login.status()).isEqualTo(200);
    }

    @Test
    void 현재_비밀번호가_틀리면_400_INVALID_PASSWORD() throws Exception {
        Res res = change("wrong-password", NEW_PASSWORD);
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.code()).isEqualTo("INVALID_PASSWORD");
        assertThat(api.login(email).status()).isEqualTo(200); // 그대로
    }

    @Test
    void 새_비밀번호는_회원가입과_같은_규칙() throws Exception {
        assertThat(change(TestApi.PASSWORD, "short").code()).isEqualTo("VALIDATION_FAILED");      // 8자 미만
        assertThat(change(TestApi.PASSWORD, "가".repeat(30)).code()).isEqualTo("VALIDATION_FAILED"); // 72바이트 초과
        assertThat(change(TestApi.PASSWORD, "").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(api.send("PATCH", "/api/v1/users/me/password",
                "{ \"currentPassword\": \"x\", \"newPassword\": \"new-password-2\" }", null).code())
                .isEqualTo("AUTH_REQUIRED");
    }

    private Res change(String current, String next) throws Exception {
        return api.send("PATCH", "/api/v1/users/me/password",
                "{ \"currentPassword\": \"%s\", \"newPassword\": \"%s\" }".formatted(current, next), token);
    }
}
