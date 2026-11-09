package kr.astra.llm.client;

import static org.assertj.core.api.Assertions.*;

import kr.astra.llm.service.StructuredJson;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class LlmProviderSelectionTest {
    final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(StructuredJson.class, () -> new StructuredJson(JsonMapper.builder().build()))
            .withUserConfiguration(AnthropicLlmClient.class, GeminiLlmClient.class)
            .withPropertyValues("astra.ai.model=claude-test", "astra.ai.anthropic-api-key=", "astra.ai.gemini-api-key=");

    @Test void geminiSelectionCreatesOnlyGeminiClient() {
        context.withPropertyValues("astra.ai.provider=gemini").run(c -> {
            assertThat(c).hasSingleBean(LlmClient.class).hasSingleBean(GeminiLlmClient.class).doesNotHaveBean(AnthropicLlmClient.class);
        });
    }

    @Test void anthropicSelectionAndLegacyMissingSettingPreserveClaude() {
        context.withPropertyValues("astra.ai.provider=anthropic").run(c ->
                assertThat(c).hasSingleBean(LlmClient.class).hasSingleBean(AnthropicLlmClient.class).doesNotHaveBean(GeminiLlmClient.class));
        context.run(c -> assertThat(c).hasSingleBean(AnthropicLlmClient.class).doesNotHaveBean(GeminiLlmClient.class));
    }

    @Test void unknownProviderDoesNotSilentlyFallback() {
        context.withPropertyValues("astra.ai.provider=typo").run(c -> assertThat(c).doesNotHaveBean(LlmClient.class));
    }
}
