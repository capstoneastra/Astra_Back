package kr.astra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import kr.astra.global.security.JwtProvider;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 로그인이 필요한 SSE(SseEmitter) 스트림이 끝까지 전달되는지 (SecurityConfig 의 ASYNC·ERROR dispatch 허용).
 * <p>
 * SseEmitter 는 응답을 마칠 때 비동기 재요청(ASYNC dispatch)을 하는데, JWT 필터는 그때 다시 돌지 않습니다.
 * 이를 막으면 스트림이 이미 나가는 중에 Access Denied 가 나서 연결이 끊깁니다 (BE-C AI 상담 스트리밍에서 발견).
 * LLM 없이 같은 상황을 만들려고 테스트 전용 SSE API 를 둡니다.
 */
@Import({TestcontainersConfiguration.class, SseAsyncDispatchTest.SseTestController.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SseAsyncDispatchTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JwtProvider jwtProvider;

    @Test
    void 로그인한_사용자의_SSE_스트림이_마지막_이벤트까지_끊기지_않는다() throws Exception {
        String token = jwtProvider.createAccessToken(1L, UserRole.COMPANY_USER, null);
        HttpResponse<String> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/test-sse"))
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("event:delta", "data:one", "data:two", "event:done", "data:end");
    }

    @Test
    void 비로그인_SSE_요청은_시작하기_전에_401() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/test-sse"))
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(401);
    }

    /** 테스트 전용: 다른 스레드에서 이벤트 두 개를 보내고 done 으로 끝내는 SSE (AI 상담 스트리밍과 같은 구조) */
    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class SseTestController {

        @GetMapping(value = "/test-sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream() {
            SseEmitter emitter = new SseEmitter(10_000L);
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(50);
                    emitter.send(SseEmitter.event().name("delta").data("one"));
                    emitter.send(SseEmitter.event().name("delta").data("two"));
                    emitter.send(SseEmitter.event().name("done").data("end"));
                    emitter.complete();
                } catch (IOException | InterruptedException e) {
                    emitter.completeWithError(e);
                }
            });
            return emitter;
        }
    }
}
