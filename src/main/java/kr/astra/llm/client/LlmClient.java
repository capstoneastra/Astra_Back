package kr.astra.llm.client;
import java.util.Map;
import java.util.function.Consumer;

/** Provider boundary; tests replace this interface without making paid requests. */
public interface LlmClient {
    record Request(String system, String input, Map<String, Object> schema, String effort) {}
    record Reply(String text, String model, String requestId, long inputTokens, long outputTokens) {}
    Reply complete(Request request);
    Reply stream(Request request, Consumer<String> onText);
}
