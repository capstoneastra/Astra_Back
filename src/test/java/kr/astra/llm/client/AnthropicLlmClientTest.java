package kr.astra.llm.client;
import static org.assertj.core.api.Assertions.*;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import kr.astra.global.exception.*;
import org.junit.jupiter.api.*;

/** Real SDK against a local HTTP double. No external provider or paid requests. */
class AnthropicLlmClientTest {
    HttpServer server;
    AnthropicLlmClient client;
    String body;
    String contentType="application/json";
    int status=200;
    int delay;
    AtomicReference<String> request=new AtomicReference<>();
    AtomicReference<String> beta=new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/messages",exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            beta.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
            if (delay > 0) try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.getResponseHeaders().set("Content-Type",contentType);
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length);
            try (var out=exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        client=client(Duration.ofSeconds(3));
    }
    AnthropicLlmClient client(Duration timeout) {
        return new AnthropicLlmClient(AnthropicOkHttpClient.builder().apiKey("local-test-key")
                .baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).maxRetries(0).timeout(timeout).build(),"configured-model");
    }
    @AfterEach void close() { client.close(); server.stop(0); }
    String message(String stop,String content) {
        return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"actual-model\",\"content\":"+content
                +",\"stop_reason\":"+stop+",\"stop_sequence\":null,\"usage\":{\"input_tokens\":2,\"output_tokens\":1}}";
    }
    LlmClient.Request request() { return new LlmClient.Request("system","input",null,"low"); }
    @Test void completeUsesConfiguredModelEffortAndFallbackHeaderButReportsActualModel() {
        body=message("\"end_turn\"","[{\"type\":\"text\",\"text\":\"ok\"}]");
        var reply=client.complete(new LlmClient.Request("system","input",Map.of("type","object","properties",Map.of(),"additionalProperties",false),"medium"));
        assertThat(reply.text()).isEqualTo("ok");
        assertThat(reply.model()).isEqualTo("actual-model");
        assertThat(request.get()).contains("configured-model","output_config","json_schema","medium","fallbacks");
        assertThat(beta.get()).contains("server-side-fallback-2026-07-01");
    }
    @Test void refusedOrTruncatedOutputAndHttpErrorAreExplicitFailures() {
        for (String reason:List.of("refusal","max_tokens")) {
            body=message("\""+reason+"\"","[{\"type\":\"text\",\"text\":\"partial\"}]");
            assertThatThrownBy(() -> client.complete(request())).isInstanceOf(BusinessException.class);
        }
        status=500; body="{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"upstream detail\"}}";
        assertThatThrownBy(() -> client.complete(request())).isInstanceOfSatisfying(BusinessException.class,
                e -> { assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_UPSTREAM_ERROR); assertThat(e.getMessage()).doesNotContain("upstream detail"); });
    }
    @Test void rateLimitIsTooManyRequests() {
        status=429; body="{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"upstream detail\"}}";
        assertThatThrownBy(() -> client.complete(request())).isInstanceOfSatisfying(BusinessException.class,
                e -> { assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS); assertThat(e.getMessage()).doesNotContain("upstream detail"); });
    }
    String event(String name,String data) { return "event: "+name+"\ndata: "+data+"\n\n"; }
    String stream(boolean stopped) {
        return event("message_start","{\"type\":\"message_start\",\"message\":"+message("null","[]")+"}")
                + event("content_block_start","{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                + event("content_block_delta","{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}")
                + event("content_block_stop","{\"type\":\"content_block_stop\",\"index\":0}")
                + event("message_delta","{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}}")
                + (stopped ? event("message_stop","{\"type\":\"message_stop\"}") : "");
    }
    @Test void streamRequiresTerminalEventAndReportsActualModel() {
        contentType="text/event-stream"; body=stream(true);
        List<String> tokens=new ArrayList<>();
        assertThat(client.stream(request(),tokens::add).model()).isEqualTo("actual-model");
        assertThat(tokens).containsExactly("ok");
        body=stream(false);
        assertThatThrownBy(() -> client.stream(request(),t -> {})).isInstanceOf(BusinessException.class);
    }
    @Test void timeoutDoesNotBecomeSuccess() {
        client.close(); client=client(Duration.ofMillis(50));
        delay=300; body=message("\"end_turn\"","[{\"type\":\"text\",\"text\":\"late\"}]");
        assertThatThrownBy(() -> client.complete(request())).isInstanceOf(BusinessException.class);
    }
}
