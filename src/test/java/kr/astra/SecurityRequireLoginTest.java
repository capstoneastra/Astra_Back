package kr.astra;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import kr.astra.global.security.JwtProvider;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * astra.security.require-login=true (기본값) 일 때 api-spec.md §0-4 권한 규칙 확인
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"astra.security.require-login=true",
                "astra.jwt.secret=" + SecurityRequireLoginTest.TEST_SECRET})
class SecurityRequireLoginTest {

    static final String TEST_SECRET = "test-only-secret-key-0123456789-abcdef";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JwtProvider jwtProvider;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 비로그인으로_보호된_API_를_호출하면_401_AUTH_REQUIRED() throws Exception {
        HttpResponse<String> res = get("/api/v1/equipment", null);
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body())
                .contains("\"success\":false")
                .contains("\"code\":\"AUTH_REQUIRED\"");
    }

    @Test
    void 인증_API_와_Swagger_는_비로그인도_접근_가능() throws Exception {
        // GET /auth/login 은 없는 메서드라 405 지만, 401 로 막히지 않는 것이 핵심
        assertThat(get("/api/v1/auth/login", null).statusCode()).isNotEqualTo(401);
        assertThat(get("/api/v1/auth/institutions", null).statusCode()).isEqualTo(200);
        assertThat(get("/v3/api-docs", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @Test
    void 로그아웃은_auth_아래지만_로그인이_필요하다() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri("/api/v1/auth/logout"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{ \"refreshToken\": \"x\" }")).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("\"code\":\"AUTH_REQUIRED\"");
    }

    @Test
    void 만료된_access_토큰은_401_TOKEN_EXPIRED() throws Exception {
        Instant past = Instant.now().minusSeconds(7200);
        String expired = Jwts.builder()
                .subject("1").claim("role", "COMPANY_USER")
                .issuedAt(Date.from(past)).expiration(Date.from(past.plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        HttpResponse<String> res = get("/api/v1/users/me", expired);
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("\"code\":\"TOKEN_EXPIRED\"");
    }

    @Test
    void 위조된_토큰과_refresh_토큰은_access_토큰으로_쓸_수_없다() throws Exception {
        String forged = Jwts.builder().subject("1").claim("role", "ADMIN")
                .signWith(Keys.hmacShaKeyFor("another-secret-key-0123456789-abcdefgh".getBytes(StandardCharsets.UTF_8)))
                .compact();
        assertThat(get("/api/v1/users/me", forged).body()).contains("\"code\":\"AUTH_REQUIRED\"");
        assertThat(get("/api/v1/users/me", jwtProvider.createRefreshToken(1L)).body())
                .contains("\"code\":\"AUTH_REQUIRED\"");
    }

    @Test
    void 유효한_access_토큰이면_보호된_API_를_통과한다() throws Exception {
        // 인증만 통과하면 되므로 없는 주소로 404 를 확인 (401 이 아님)
        String token = jwtProvider.createAccessToken(1L, UserRole.COMPANY_USER, null);
        HttpResponse<String> res = get("/api/v1/no-such-path", token);
        assertThat(res.statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path, String accessToken) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(uri(path)).GET();
        if (accessToken != null) {
            req.header("Authorization", "Bearer " + accessToken);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
