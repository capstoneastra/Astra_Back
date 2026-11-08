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
 * CORS: 프론트(기본 http://localhost:5173)가 브라우저에서 API 를 부를 때 (CorsConfig).
 * 브라우저는 Authorization 헤더가 붙은 요청 전에 OPTIONS 사전 요청(preflight)을 보냅니다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsTest {

    private static final String FRONT = "http://localhost:5173";

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 허용된_프론트_주소의_사전_요청은_통과하고_Authorization_헤더와_메서드를_허용한다() throws Exception {
        HttpResponse<String> res = preflight("/api/v1/equipment/48", FRONT, "PATCH", "authorization,content-type");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(header(res, "Access-Control-Allow-Origin")).isEqualTo(FRONT);
        assertThat(header(res, "Access-Control-Allow-Methods")).contains("PATCH").contains("DELETE");
        assertThat(header(res, "Access-Control-Allow-Headers").toLowerCase()).contains("authorization");
        assertThat(header(res, "Access-Control-Max-Age")).isEqualTo("3600");
        // 쿠키를 쓰지 않으므로 credentials 허용 안 함
        assertThat(res.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }

    @Test
    void 허용되지_않은_주소의_사전_요청은_거절한다() throws Exception {
        HttpResponse<String> res = preflight("/api/v1/equipment", "http://evil.example", "GET", "authorization");
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void 로그인이_필요한_401_응답에도_CORS_헤더가_붙어_프론트가_읽을_수_있다() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(url("/api/v1/equipment"))
                .header("Origin", FRONT).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(header(res, "Access-Control-Allow-Origin")).isEqualTo(FRONT);
        assertThat(res.body()).contains("AUTH_REQUIRED");
    }

    @Test
    void 로그인_없이_부르는_가입용_API도_허용된다() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(url("/api/v1/auth/institutions"))
                .header("Origin", FRONT).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(header(res, "Access-Control-Allow-Origin")).isEqualTo(FRONT);
    }

    private HttpResponse<String> preflight(String path, String origin, String method, String headers)
            throws Exception {
        return http.send(HttpRequest.newBuilder(url(path))
                .header("Origin", origin)
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", headers)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String header(HttpResponse<?> res, String name) {
        return res.headers().firstValue(name).orElseThrow(() -> new AssertionError(name + " 헤더가 없습니다"));
    }
}
