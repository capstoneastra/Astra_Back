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
    /** 남은 시간이 이보다 짧으면 새 모델을 부르지 않고 바로 실패로 돌려줌 */
    private static final long MIN_ATTEMPT_MILLIS = 1000;
    private final HttpClient http;
    private final String key;
    private final List<String> models;
    private final URI baseUri;
    private final Duration timeout;
    private final StructuredJson json;
    private final LlmConcurrencyLimiter limiter;
    /** Gemini 모델이 모두 과부하·한도·시간 초과일 때 마지막으로 부르는 Claude. ANTHROPIC_API_KEY 가 없으면 null */
    private final LlmClient fallback;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("gemini-deadline").factory());

    @Autowired
    public GeminiLlmClient(@Value("${astra.ai.gemini-api-key:}") String key,
            @Value("${astra.ai.gemini-model:gemini-3.8-flash}") String model,
            @Value("${astra.ai.gemini-models:}") String models,
            @Value("${astra.ai.model-timeout-seconds:12}") long modelTimeoutSeconds,
            @Value("${astra.ai.anthropic-api-key:}") String anthropicKey,
            @Value("${astra.ai.model:}") String anthropicModel, StructuredJson json,
            @Value("${astra.ai.max-concurrent-calls:8}") int maxConcurrentCalls,
            @Value("${astra.ai.concurrency-wait-seconds:20}") long concurrencyWaitSeconds) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), key, modelList(models, model),
                URI.create("https://generativelanguage.googleapis.com/v1beta/"), Duration.ofSeconds(modelTimeoutSeconds), json,
                new LlmConcurrencyLimiter(maxConcurrentCalls, Duration.ofSeconds(concurrencyWaitSeconds)),
                anthropicKey == null || anthropicKey.isBlank() || anthropicModel == null || anthropicModel.isBlank() ? null
                        : new AnthropicLlmClient(anthropicKey, anthropicModel, maxConcurrentCalls, concurrencyWaitSeconds));
    }

    GeminiLlmClient(HttpClient http, String key, String model, URI baseUri, Duration timeout, StructuredJson json) {
        this(http, key, model, baseUri, timeout, json, new LlmConcurrencyLimiter(8, Duration.ofSeconds(20)));
    }

    GeminiLlmClient(HttpClient http, String key, String model, URI baseUri, Duration timeout, StructuredJson json,
            LlmConcurrencyLimiter limiter) {
        this(http, key, List.of(model == null ? "" : model), baseUri, timeout, json, limiter, null);
    }

    GeminiLlmClient(HttpClient http, String key, List<String> models, URI baseUri, Duration timeout, StructuredJson json,
            LlmConcurrencyLimiter limiter, LlmClient fallback) {
        if (models.isEmpty() || models.stream().anyMatch(m -> m == null || !m.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")))
            throw new IllegalArgumentException("Gemini 모델 ID를 확인해 주세요.");
        this.http = http; this.key = key; this.models = List.copyOf(models);
        this.baseUri = baseUri; this.timeout = timeout; this.json = json; this.limiter = limiter; this.fallback = fallback;
    }

    /** GEMINI_MODELS(쉼표로 나눈 순서)가 있으면 그 순서, 없으면 GEMINI_MODEL 하나 */
    static List<String> modelList(String models, String model) {
        List<String> list = models == null ? List.of() : Arrays.stream(models.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
        return list.isEmpty() ? List.of(model == null ? "" : model.strip()) : list;
    }

    /** 503(과부하)·429(한도)·시간 초과: 같은 모델을 다시 부르지 않고 다음 모델로 넘길 실패 */
    private static final class Switchable extends RuntimeException {
        final String reason;
        Switchable(String reason) { super(reason, null, false, false); this.reason = reason; }
    }

    @Override public Reply complete(Request request) { return execute(request, null); }
    @Override public Reply stream(Request request, Consumer<String> onText) {
        return execute(request, Objects.requireNonNull(onText));
    }

    private Reply execute(Request request, Consumer<String> onText) {
        if (Thread.currentThread().isInterrupted()) throw failure();
        long started = System.nanoTime();
        int total = models.size() + (fallback == null ? 0 : 1);
        // 스트리밍은 글자를 한 번이라도 보냈으면 다른 모델로 넘기지 않음 (답이 섞이지 않게)
        AtomicBoolean emitted = new AtomicBoolean();
        Consumer<String> sink = onText == null ? null : chunk -> { emitted.set(true); onText.accept(chunk); };
        if (key != null && !key.isBlank()) {
            Reply reply = limiter.call(() -> tryModels(request, sink, emitted, started, total));
            if (reply != null) return reply;
        } else if (fallback == null) {
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR, "Gemini API 키가 설정되지 않았습니다.", null);
        }
        Duration budget = LlmDeadline.remaining(timeout);
        if (budget.toMillis() < MIN_ATTEMPT_MILLIS) throw expired(total, total, started);
        try {
            Reply reply = LlmDeadline.within(budget,
                    () -> onText == null ? fallback.complete(request) : fallback.stream(request, onText));
            log.info("AI 응답 완료 provider=anthropic model={} attempt={}/{} elapsedMs={}", reply.model(), total, total, millis(started));
            return reply;
        } catch (BusinessException e) {
            log.warn("Claude 대체 호출도 실패 attempt={}/{} code={} elapsedMs={}", total, total, e.getErrorCode(), millis(started));
            throw failure();
        }
    }

    /** 순서대로 모델을 부름. 넘길 수 있는 실패만 계속 넘기고, 모두 그렇게 실패하면 null (Claude 대체로) */
    private Reply tryModels(Request request, Consumer<String> sink, AtomicBoolean emitted, long started, int total) {
        for (int i = 0; i < models.size(); i++) {
            String model = models.get(i);
            Duration budget = LlmDeadline.remaining(timeout);
            if (budget.toMillis() < MIN_ATTEMPT_MILLIS) throw expired(i + 1, total, started);
            try {
                Reply reply = attempt(model, request, sink, budget);
                log.info("AI 응답 완료 provider=gemini model={} attempt={}/{} elapsedMs={}", model, i + 1, total, millis(started));
                return reply;
            } catch (Switchable e) {
                if (emitted.get()) throw failure();
                log.warn("Gemini {} {}, 다음 모델로 넘깁니다 attempt={}/{} elapsedMs={}", model, e.reason, i + 1, total, millis(started));
            }
        }
        if (fallback == null) {
            log.warn("Gemini 모델이 모두 응답하지 않았습니다 ({}개 시도, elapsedMs={})", total, millis(started));
            throw failure();
        }
        return null;
    }

    private BusinessException expired(int attempt, int total, long started) {
        log.warn("AI 응답 마감 시간이 지나 더 시도하지 않습니다 (attempt={}/{} 전, elapsedMs={})", attempt, total, millis(started));
        return failure();
    }
    private static long millis(long started) { return (System.nanoTime() - started) / 1_000_000; }

    private Reply attempt(String model, Request request, Consumer<String> onText, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) throw failure();
        boolean streaming = onText != null;
        var endpoint = baseUri.resolve("models/" + model
                + (streaming ? ":streamGenerateContent?alt=sse" : ":generateContent"));
        var call = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json").header("x-goog-api-key", key)
                .header("Accept", streaming ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(request))).build();
        long started = System.nanoTime();
        AtomicBoolean expired = new AtomicBoolean();
        var pending = http.sendAsync(call, HttpResponse.BodyHandlers.ofInputStream());
        try {
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    int status = response.statusCode();
                    log.warn("Gemini 응답 오류 status={} message={}", status, errorSnippet(body));
                    if (status == 429) throw new Switchable("한도 초과(429)");
                    if (status == 503) throw new Switchable("과부하(503)");
                    if (status == 504) throw new Switchable("시간 초과(504)");
                    throw failure();
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!contentType.toLowerCase(Locale.ROOT).startsWith(streaming ? "text/event-stream" : "application/json"))
                    throw failure();
                // HttpRequest.timeout alone does not cover reading a streamed body after headers arrive.
                long remaining = timeout.toNanos() - (System.nanoTime() - started);
                if (remaining <= 0) throw new Switchable("시간 초과");
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
                    if (Thread.currentThread().isInterrupted()) throw failure();
                    if (expired.get()) throw new Switchable("시간 초과");
                    return result.reply();
                } finally { deadline.cancel(false); }
            }
        } catch (InterruptedException e) {
            pending.cancel(true); Thread.currentThread().interrupt(); throw failure();
        } catch (Switchable e) { throw e; }
        catch (Exception e) {
            // 마감 시간에 본문을 닫아 생긴 읽기 오류, 응답 헤더 대기 시간 초과는 다음 모델로 넘김
            if (!Thread.currentThread().isInterrupted() && (expired.get() || timedOut(e))) throw new Switchable("시간 초과");
            if (e instanceof BusinessException b) throw b;
            log.warn("Gemini 호출 실패: {}", e.getClass().getSimpleName());
            throw failure();
        }
        finally {
            // Cancellation may race with headers arriving; close a body delivered at that boundary too.
            pending.thenAccept(response -> { try { response.body().close(); } catch (IOException ignored) { } });
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private static boolean timedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof TimeoutException || t instanceof HttpTimeoutException) return true;
        return false;
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
    @PreDestroy public void close() {
        deadlines.shutdownNow(); http.shutdownNow();
        if (fallback instanceof AnthropicLlmClient claude) claude.close();
    }
}
