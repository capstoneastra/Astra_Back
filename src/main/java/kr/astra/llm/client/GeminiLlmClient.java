package kr.astra.llm.client;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.llm.service.StructuredJson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 임시 Gemini Developer API 어댑터. 기존 서비스의 JSON 검증과 SSE 계약은 그대로 사용합니다. */
@Slf4j
@Component
@ConditionalOnProperty(name = "astra.ai.provider", havingValue = "gemini")
public class GeminiLlmClient implements LlmClient {
    private final HttpClient http;
    private final String key;
    private final String model;
    private final URI baseUri;
    private final Duration timeout;
    private final StructuredJson json;
    private final LlmConcurrencyLimiter limiter;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("gemini-deadline").factory());

    @Autowired
    public GeminiLlmClient(@Value("${astra.ai.gemini-api-key:}") String key,
            @Value("${astra.ai.gemini-model:gemini-3.8-flash}") String model, StructuredJson json,
            @Value("${astra.ai.max-concurrent-calls:8}") int maxConcurrentCalls,
            @Value("${astra.ai.concurrency-wait-seconds:20}") long concurrencyWaitSeconds) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), key, model,
                URI.create("https://generativelanguage.googleapis.com/v1beta/"), Duration.ofSeconds(45), json,
                new LlmConcurrencyLimiter(maxConcurrentCalls, Duration.ofSeconds(concurrencyWaitSeconds)));
    }

    GeminiLlmClient(HttpClient http, String key, String model, URI baseUri, Duration timeout, StructuredJson json) {
        this(http, key, model, baseUri, timeout, json, new LlmConcurrencyLimiter(8, Duration.ofSeconds(20)));
    }

    GeminiLlmClient(HttpClient http, String key, String model, URI baseUri, Duration timeout, StructuredJson json,
            LlmConcurrencyLimiter limiter) {
        if (model == null || !model.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*"))
            throw new IllegalArgumentException("Gemini 모델 ID를 확인해 주세요.");
        this.http = http; this.key = key; this.model = model;
        this.baseUri = baseUri; this.timeout = timeout; this.json = json; this.limiter = limiter;
    }

    /** 503(과부하)만 표시하는 내부 신호. 비스트리밍 호출에서 한 번만 재시도합니다. */
    private static final class Overloaded extends RuntimeException {
        Overloaded() { super(null, null, false, false); }
    }

    @Override public Reply complete(Request request) { return execute(request, null); }
    @Override public Reply stream(Request request, Consumer<String> onText) {
        return execute(request, Objects.requireNonNull(onText));
    }

    private Reply execute(Request request, Consumer<String> onText) {
        if (key == null || key.isBlank())
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR, "Gemini API 키가 설정되지 않았습니다.", null);
        if (Thread.currentThread().isInterrupted()) throw failure();
        return limiter.call(() -> {
            long started = System.nanoTime();
            for (int attempt = 0; ; attempt++) {
                Duration budget = timeout.minusNanos(System.nanoTime() - started);
                try { return attempt(request, onText, budget); }
                catch (Overloaded e) {
                    // 스트리밍은 이미 일부 토큰을 보냈을 수 있으므로 재시도하지 않음
                    long backoff = ThreadLocalRandom.current().nextLong(200, 800);
                    Duration left = timeout.minusNanos(System.nanoTime() - started);
                    if (onText != null || attempt > 0 || left.toMillis() < backoff + 1000) throw failure();
                    log.info("Gemini 과부하(503), {}ms 후 한 번 재시도합니다", backoff);
                    try { Thread.sleep(backoff); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw failure(); }
                }
            }
        });
    }

    private Reply attempt(Request request, Consumer<String> onText, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) throw failure();
        boolean streaming = onText != null;
        var endpoint = baseUri.resolve("models/" + model
                + (streaming ? ":streamGenerateContent?alt=sse" : ":generateContent"));
        var call = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json").header("x-goog-api-key", key)
                .header("Accept", streaming ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(request))).build();
        long started = System.nanoTime();
        var pending = http.sendAsync(call, HttpResponse.BodyHandlers.ofInputStream());
        try {
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    int status = response.statusCode();
                    log.warn("Gemini 응답 오류 status={} message={}", status, errorSnippet(body));
                    if (status == 429) throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                            "AI 요청 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.", null);
                    if (status == 503) throw new Overloaded();
                    throw failure();
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!contentType.toLowerCase(Locale.ROOT).startsWith(streaming ? "text/event-stream" : "application/json"))
                    throw failure();
                // HttpRequest.timeout alone does not cover reading a streamed body after headers arrive.
                AtomicBoolean expired = new AtomicBoolean();
                long remaining = timeout.toNanos() - (System.nanoTime() - started);
                if (remaining <= 0) throw failure();
                var deadline = deadlines.schedule(() -> {
                    expired.set(true);
                    try { body.close(); } catch (IOException ignored) { }
                }, remaining, TimeUnit.NANOSECONDS);
                try (var reader = new BufferedReader(new InputStreamReader(limited(body), StandardCharsets.UTF_8))) {
                    var result = new ResponseState();
                    if (streaming) readEvents(reader, result, onText);
                    else {
                        StringBuilder raw = new StringBuilder();
                        char[] buffer = new char[4096];
                        int count;
                        while ((count = reader.read(buffer)) != -1) {
                            raw.append(buffer, 0, count);
                            if (raw.length() > 100000 || Thread.currentThread().isInterrupted()) throw failure();
                        }
                        result.accept(json.parse(raw.toString()), null);
                    }
                    if (expired.get() || Thread.currentThread().isInterrupted()) throw failure();
                    return result.reply();
                } finally { deadline.cancel(false); }
            }
        } catch (InterruptedException e) {
            pending.cancel(true); Thread.currentThread().interrupt(); throw failure();
        } catch (BusinessException | Overloaded e) { throw e; }
        catch (Exception e) {
            log.warn("Gemini 호출 실패: {}", e.getClass().getSimpleName());
            throw failure();
        }
        finally {
            // Cancellation may race with headers arriving; close a body delivered at that boundary too.
            pending.thenAccept(response -> { try { response.body().close(); } catch (IOException ignored) { } });
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    /** 오류 응답의 앞부분만 진단용으로 남김. API 키가 섞여 있으면 가림 */
    private String errorSnippet(InputStream body) {
        try {
            String raw = new String(body.readNBytes(4096), StandardCharsets.UTF_8);
            String message = raw;
            if (raw.strip().startsWith("{")) try {
                var node = json.parse(raw).path("error").path("message");
                if (node.isString()) message = node.asString();
            } catch (BusinessException ignored) { /* 잘린 JSON 이면 원문 앞부분 */ }
            if (key != null && !key.isBlank()) message = message.replace(key, "***");
            message = message.replaceAll("\\s+", " ").strip();
            return message.length() > 200 ? message.substring(0, 200) + "..." : message;
        } catch (IOException e) { return "(본문 없음)"; }
    }

    private String payload(Request request) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("candidateCount", 1);
        config.put("maxOutputTokens", 8192);
        // Gemini 3.x uses thinkingLevel; Anthropic-only effort/fallback headers are never sent.
        if (!Set.of("low", "medium", "high").contains(request.effort())) throw failure();
        config.put("thinkingConfig", Map.of("thinkingLevel", request.effort().toUpperCase(Locale.ROOT), "includeThoughts", false));
        if (request.schema() != null) {
            config.put("responseMimeType", "application/json");
            config.put("responseJsonSchema", request.schema());
        }
        return json.write(Map.of("systemInstruction", Map.of("parts", List.of(Map.of("text", request.system()))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", request.input())))),
                "generationConfig", config));
    }

    private void readEvents(BufferedReader reader, ResponseState state, Consumer<String> onText) throws IOException {
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (Thread.currentThread().isInterrupted()) throw failure();
            if (line.isEmpty()) {
                if (!data.isEmpty()) { state.accept(json.parse(data.toString()), onText); data.setLength(0); }
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (value.startsWith(" ")) value = value.substring(1);
                if (!data.isEmpty()) data.append('\n');
                data.append(value);
                if (data.length() > 100000) throw failure();
            }
        }
        // An incomplete SSE frame or EOF without a STOP candidate must never become success.
        if (!data.isEmpty()) throw failure();
    }

    private final class ResponseState {
        final StringBuilder text = new StringBuilder();
        String actualModel;
        String responseId;
        long inputTokens;
        long outputTokens;
        boolean stopped;

        void accept(JsonNode root, Consumer<String> onText) {
            if (root.has("error")) throw failure();
            String blocked = root.path("promptFeedback").path("blockReason").asString("");
            if (!blocked.isEmpty() && !blocked.equals("BLOCK_REASON_UNSPECIFIED")) throw failure();
            if (root.has("modelVersion")) actualModel = json.text(root, "modelVersion", true, 200);
            if (root.has("responseId")) responseId = json.text(root, "responseId", true, 500);
            var usage = root.get("usageMetadata");
            if (usage != null) {
                if (!usage.isObject()) throw failure();
                if (usage.has("promptTokenCount")) inputTokens = count(usage, "promptTokenCount");
                if (usage.has("candidatesTokenCount") || usage.has("thoughtsTokenCount"))
                    outputTokens = count(usage, "candidatesTokenCount") + count(usage, "thoughtsTokenCount");
            }
            var candidates = root.get("candidates");
            if (candidates == null) return; // usage-only event; reply() still requires a completed candidate.
            if (!candidates.isArray() || candidates.size() != 1) throw failure();
            var candidate = candidates.get(0);
            String reason = json.text(candidate, "finishReason", false, 100);
            if (reason != null && !reason.equals("STOP")) throw failure();
            var parts = candidate.path("content").get("parts");
            if (parts != null) {
                if (!parts.isArray()) throw failure();
                for (var part : parts) {
                    if (part.path("thought").asBoolean(false)) continue;
                    String chunk = json.text(part, "text", false, 100000);
                    if (chunk == null) throw failure();
                    if (stopped && !chunk.isEmpty()) throw failure();
                    text.append(chunk);
                    if (text.length() > 100000) throw failure();
                    if (onText != null) onText.accept(chunk);
                }
            }
            if (reason != null) stopped = true;
        }

        long count(JsonNode node, String field) {
            var value = node.get(field);
            if (value == null) return 0;
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0 || value.asLong() > Integer.MAX_VALUE)
                throw failure();
            return value.asLong();
        }

        Reply reply() {
            if (!stopped || text.toString().isBlank() || actualModel == null || responseId == null) throw failure();
            return new Reply(text.toString(), actualModel, responseId, inputTokens, outputTokens);
        }
    }

    private InputStream limited(InputStream body) {
        return new FilterInputStream(body) {
            private int bytes;
            @Override public int read() throws IOException {
                int value = in.read();
                if (value != -1 && ++bytes > 1_000_000) throw new IOException("LLM response too large");
                return value;
            }
            @Override public int read(byte[] b, int offset, int length) throws IOException {
                int count = in.read(b, offset, Math.min(length, 8192));
                if (count > 0 && (bytes += count) > 1_000_000) throw new IOException("LLM response too large");
                return count;
            }
        };
    }

    private BusinessException failure() { return new BusinessException(ErrorCode.AI_UPSTREAM_ERROR); }
    @PreDestroy public void close() { deadlines.shutdownNow(); http.shutdownNow(); }
}
