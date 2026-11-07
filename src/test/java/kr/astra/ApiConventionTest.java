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
 * 실제 서버를 띄워 공통 규약(api-spec.md §0)을 확인합니다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiConventionTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 없는_주소는_공통_실패_형식으로_404() throws Exception {
        HttpResponse<String> res = get("/api/v1/no-such-path");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body())
                .contains("\"success\":false")
                .contains("\"code\":\"NOT_FOUND\"");
    }

    @Test
    void Swagger_문서와_헬스체크는_로그인_없이_열린다() throws Exception {
        HttpResponse<String> docs = get("/v3/api-docs");
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("ASTRA API");

        assertThat(get("/swagger-ui.html").statusCode()).isIn(200, 302);
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
