package kr.astra;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * astra.security.require-login=true (운영·JWT 적용 후) 일 때 api-spec.md §0-4 권한 규칙 확인
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "astra.security.require-login=true")
class SecurityRequireLoginTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 비로그인으로_보호된_API_를_호출하면_401_AUTH_REQUIRED() throws Exception {
        HttpResponse<String> res = get("/api/v1/equipment");
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body())
                .contains("\"success\":false")
                .contains("\"code\":\"AUTH_REQUIRED\"");
    }

    @Test
    void 인증_API_와_Swagger_는_비로그인도_접근_가능() throws Exception {
        // /auth 아래는 컨트롤러가 아직 없어 404 지만, 401 로 막히지 않는 것이 핵심
        assertThat(get("/api/v1/auth/login").statusCode()).isNotEqualTo(401);
        assertThat(get("/v3/api-docs").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
