package kr.astra;

import static kr.astra.TestApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import kr.astra.TestApi.Res;
import kr.astra.TestMailConfiguration.CapturingEmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 가입·로그인 입력 방어: 긴 비밀번호, 잘못된 Content-Type, 동시 인증 코드 요청.
 */
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthHardeningTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CapturingEmailSender mail;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, mail);
    }

    @Test
    void 비밀번호가_72바이트를_넘으면_글자_수가_적어도_400_password() throws Exception {
        String email = uniqueEmail();
        String password = "가".repeat(30); // 30자지만 UTF-8 90바이트 (BCrypt 한도 72바이트)
        Res res = api.post("/api/v1/auth/signup", """
                { "email": "%s", "password": "%s", "name": "홍길동", "role": "COMPANY_USER",
                  "verificationToken": "%s",
                  "organization": { "name": "%s", "businessRegNo": "111-22-33333" } }
                """.formatted(email, password, api.verificationToken(email), "테스트 기업 " + UUID.randomUUID()), null);
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(res.details().path("fields").get(0).path("field").asString()).isEqualTo("password");
    }

    @Test
    void JSON_이_아닌_Content_Type_은_500이_아니라_400() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("hello")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"code\":\"VALIDATION_FAILED\"");
    }

    @Test
    void 같은_이메일로_동시에_처음_요청해도_코드는_하나만_만든다() throws Exception {
        String email = uniqueEmail();
        int n = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    return api.post("/api/v1/auth/email-verifications", "{ \"email\": \"%s\" }".formatted(email), null)
                            .status();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        start.countDown();
        List<Integer> statuses = calls.stream().map(CompletableFuture::join).toList();

        assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 429).hasSize(n - 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_verifications WHERE email = ?", Long.class, email))
                .isEqualTo(1);
    }
}
