package kr.astra.llm.client;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.llm.service.StructuredJson;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

/** 로컬 HTTP 대역만 사용. 실제 Google 키나 유료 API를 호출하지 않습니다. */
class GeminiLlmClientTest {
    final StructuredJson json = new StructuredJson(JsonMapper.builder().build());
    HttpServer server;
    GeminiLlmClient client;
    String body;
    String contentType = "application/json";
    int status = 200;
    int stallAfterHeaders;
    final AtomicReference<String> request = new AtomicReference<>();
    final AtomicReference<String> key = new AtomicReference<>();
    final AtomicReference<String> path = new AtomicReference<>();
    final AtomicReference<String> anthropicHeader = new AtomicReference<>();
    final CountDownLatch headersSent = new CountDownLatch(1);

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/models/", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            key.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            path.set(exchange.getRequestURI().toString());
            anthropicHeader.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, 0);
            headersSent.countDown();
            try (var out = exchange.getResponseBody()) {
                if (stallAfterHeaders > 0) {
                    out.flush();
                    try { Thread.sleep(stallAfterHeaders); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                out.write(body.getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException ignored) { /* client timeout/cancellation */ }
        });
        server.start();
        client = client("local-test-key", Duration.ofSeconds(3));
        body = response("STOP", "정상 응답");
    }

    GeminiLlmClient client(String apiKey, Duration timeout) {
        return new GeminiLlmClient(HttpClient.newHttpClient(), apiKey, "gemini-test-model",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/"), timeout, json);
    }

    @AfterEach void close() { client.close(); server.stop(0); }
    LlmClient.Request input() { return new LlmClient.Request("system", "input", null, "low"); }
    String response(String finish, String text) {
        var candidate = new LinkedHashMap<String, Object>();
        candidate.put("content", Map.of("parts", List.of(Map.of("text", text))));
        if (finish != null) candidate.put("finishReason", finish);
        return json.write(Map.of("candidates", List.of(candidate), "modelVersion", "actual-gemini-version",
                "responseId", "response-1", "usageMetadata", Map.of("promptTokenCount", 3,
                        "candidatesTokenCount", 2, "thoughtsTokenCount", 1)));
    }
    String event(String data) { return "data: " + data + "\n\n"; }

    @Test void completeSendsSchemaAndSystemAndReportsActualMetadata() {
        var schema = StructuredJson.object(Map.of("name", StructuredJson.nullable("string")));
        body = response("STOP", "{\"name\":null}");
        var reply = client.complete(new LlmClient.Request("system", "input", schema, "medium"));
        var sent = json.parse(request.get());
        assertThat(sent.path("systemInstruction").path("parts").get(0).path("text").asString()).isEqualTo("system");
        assertThat(sent.path("contents").get(0).path("parts").get(0).path("text").asString()).isEqualTo("input");
        assertThat(sent.path("generationConfig").path("responseJsonSchema")).isEqualTo(json.parse(json.write(schema)));
        assertThat(sent.path("generationConfig").path("responseMimeType").asString()).isEqualTo("application/json");
        assertThat(sent.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asString()).isEqualTo("MEDIUM");
        assertThat(reply).isEqualTo(new LlmClient.Reply("{\"name\":null}", "actual-gemini-version", "response-1", 3, 3));
        assertThat(key.get()).isEqualTo("local-test-key");
        assertThat(path.get()).isEqualTo("/v1beta/models/gemini-test-model:generateContent");
        assertThat(anthropicHeader.get()).isNull();
        assertThat(request.get()).doesNotContain("fallbacks", "output_config");
    }

    @Test void streamPreservesWhitespaceAndNeverEmitsThoughtParts() {
        contentType = "text/event-stream; charset=utf-8";
        body = event("{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"private reasoning\"}]}}]}")
                + event(response(null, "안녕")) + event(response(null, " ")) + event(response("STOP", "하세요"));
        List<String> chunks = new ArrayList<>();
        assertThat(client.stream(input(), chunks::add).text()).isEqualTo("안녕 하세요");
        assertThat(chunks).containsExactly("안녕", " ", "하세요");
        assertThat(path.get()).endsWith(":streamGenerateContent?alt=sse");
    }

    @Test void truncatedBlockedMalformedOrHttpFailureNeverBecomesSuccess() {
        for (String invalid : List.of(response("MAX_TOKENS", "partial"), response("SAFETY", "blocked"),
                response(null, "unfinished"), "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}", "not json",
                response("STOP", "  "), response("STOP", "ok") + "{}")) {
            body = invalid;
            assertFailure(() -> client.complete(input()));
        }
        status = 429; body = "provider details and local-test-key";
        assertThatThrownBy(() -> client.complete(input())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessage()).doesNotContain(body, "local-test-key"));
    }

    @Test void streamRequiresStopAndCompleteFrameAndPropagatesDisconnect() {
        contentType = "text/event-stream";
        body = event(response(null, "partial"));
        assertFailure(() -> client.stream(input(), text -> {}));
        body = "data: " + response("STOP", "partial");
        assertFailure(() -> client.stream(input(), text -> {}));
        body = event(response("STOP", "ok"));
        assertFailure(() -> client.stream(input(), text -> { throw new IllegalStateException("disconnected"); }));
    }

    @Test void missingKeyNeverSendsRequest() {
        client.close(); client = client("", Duration.ofSeconds(1));
        assertFailure(() -> client.complete(input()));
        assertThat(request.get()).isNull();
    }

    @Test void deadlineAlsoCoversStalledStreamBodyAfterHeaders() {
        client.close(); client = client("local-test-key", Duration.ofMillis(250));
        contentType = "text/event-stream"; stallAfterHeaders = 1200;
        body = event(response("STOP", "too late"));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertFailure(() -> client.stream(input(), t -> {})));
    }

    @Test void interruptionStopsBlockedReadAndPreservesInterruptFlag() throws Exception {
        contentType = "text/event-stream"; stallAfterHeaders = 1500;
        body = event(response("STOP", "too late"));
        var result = new CompletableFuture<Boolean>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try { client.stream(input(), t -> {}); result.complete(false); }
            catch (BusinessException e) { result.complete(Thread.currentThread().isInterrupted()); }
        });
        try {
            assertThat(headersSent.await(2, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            assertThat(result.get(2, TimeUnit.SECONDS)).isTrue();
        } finally { worker.interrupt(); worker.join(2000); }
    }

    private void assertFailure(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_UPSTREAM_ERROR));
    }
}
