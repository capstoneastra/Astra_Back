package kr.astra.llm.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import kr.astra.global.exception.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
// @Component // 기존: Claude 클라이언트를 항상 등록. 임시 Gemini 전환을 위해 아래 조건부 등록으로 대체.
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "astra.ai.provider", havingValue = "anthropic", matchIfMissing = true)
public class AnthropicLlmClient implements LlmClient {
    private final AnthropicClient client;
    private final String model;
    private final LlmConcurrencyLimiter limiter;
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    @org.springframework.beans.factory.annotation.Autowired
    public AnthropicLlmClient(@Value("${astra.ai.anthropic-api-key:}") String key,
            @Value("${astra.ai.model}") String model,
            @Value("${astra.ai.max-concurrent-calls:8}") int maxConcurrentCalls,
            @Value("${astra.ai.concurrency-wait-seconds:20}") long concurrencyWaitSeconds) {
        this.model = model;
        client = key == null || key.isBlank() ? null : AnthropicOkHttpClient.builder()
                .apiKey(key).timeout(TIMEOUT).maxRetries(0).build();
        limiter = new LlmConcurrencyLimiter(maxConcurrentCalls, Duration.ofSeconds(concurrencyWaitSeconds));
    }

    public AnthropicLlmClient(String key, String model) { this(key, model, 8, 20); }

    AnthropicLlmClient(AnthropicClient client, String model) {
        this.client = client; this.model = model; this.limiter = new LlmConcurrencyLimiter(8, Duration.ofSeconds(20));
    }

    @Override
    public Reply complete(Request request) { return limiter.call(() -> doComplete(request)); }

    private Reply doComplete(Request request) {
        try {
            Message message = requiredClient().messages().create(params(request), options());
            if (!message.stopReason().filter(StopReason.END_TURN::equals).isPresent()) throw failure();
            String text = message.content().stream().flatMap(b -> b.text().stream())
                    .map(TextBlock::text).collect(java.util.stream.Collectors.joining());
            if (text.isBlank()) throw failure();
            return new Reply(text, message.model().asString(), message.id(), message.usage().inputTokens(), message.usage().outputTokens());
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw failure(e); }
    }

    @Override
    public Reply stream(Request request, Consumer<String> onText) { return limiter.call(() -> doStream(request, onText)); }

    private Reply doStream(Request request, Consumer<String> onText) {
        StringBuilder text = new StringBuilder();
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicBoolean ended = new AtomicBoolean();
        AtomicReference<String> requestId = new AtomicReference<>("");
        AtomicReference<String> actualModel = new AtomicReference<>(model);
        AtomicLong input = new AtomicLong();
        AtomicLong output = new AtomicLong();
        try (var stream = requiredClient().messages().createStreaming(params(request), options())) {
            stream.stream().forEach(event -> {
                if (Thread.currentThread().isInterrupted()) throw failure();
                event.messageStart().ifPresent(s -> {
                    requestId.set(s.message().id()); input.set(s.message().usage().inputTokens());
                    actualModel.set(s.message().model().asString());
                });
                event.contentBlockDelta().ifPresent(delta -> delta.delta().text().ifPresent(t -> {
                    text.append(t.text());
                    if (text.length() > 100000) throw failure();
                    onText.accept(t.text());
                }));
                event.messageDelta().ifPresent(d -> {
                    d.delta().stopReason().ifPresent(reason -> ended.set(StopReason.END_TURN.equals(reason)));
                    output.set(d.usage().outputTokens());
                });
                event.messageStop().ifPresent(s -> stopped.set(true));
            });
            if (!stopped.get() || !ended.get() || text.isEmpty()) throw failure();
            return new Reply(text.toString(), actualModel.get(), requestId.get(), input.get(), output.get());
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw failure(e); }
    }

    private MessageCreateParams params(Request request) {
        OutputConfig.Builder output = OutputConfig.builder().effort(OutputConfig.Effort.of(request.effort()));
        if (request.schema() != null) {
            var schema = JsonOutputFormat.Schema.builder();
            request.schema().forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
            output.format(JsonOutputFormat.builder().schema(schema.build()).build());
        }
        return MessageCreateParams.builder().model(model).maxTokens(4096)
                .system(request.system()).addUserMessage(request.input()).outputConfig(output.build())
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default")).build();
    }
    /** 기본 45초, 상담처럼 전체 마감(LlmDeadline)이 있으면 남은 시간까지만 기다림 */
    private com.anthropic.core.RequestOptions options() {
        if (!LlmDeadline.active()) return com.anthropic.core.RequestOptions.none();
        Duration budget = LlmDeadline.remaining(TIMEOUT);
        if (budget.toMillis() < 1000) throw failure();
        return com.anthropic.core.RequestOptions.builder().timeout(budget).build();
    }
    private AnthropicClient requiredClient() {
        if (client == null) throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR, "LLM API 키가 설정되지 않았습니다.", null);
        return client;
    }
    private BusinessException failure() { return new BusinessException(ErrorCode.AI_UPSTREAM_ERROR); }
    /** 상태 코드와 오류 종류만 기록 (키·본문 원문 제외). 429 는 한도 초과로 알려 줌 */
    private BusinessException failure(Exception e) {
        if (e instanceof com.anthropic.errors.AnthropicServiceException service) {
            log.warn("Anthropic 응답 오류 status={} type={}", service.statusCode(),
                    service.errorType().map(Object::toString).orElse("?"));
            if (service.statusCode() == 429) return new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "AI 요청 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.", null);
        } else log.warn("Anthropic 호출 실패: {}", e.getClass().getSimpleName());
        return failure();
    }
    @PreDestroy public void close() { if (client != null) client.close(); }
}
