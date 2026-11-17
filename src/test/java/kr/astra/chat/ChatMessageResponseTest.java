package kr.astra.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kr.astra.chat.dto.ChatResponse;
import kr.astra.chat.entity.ChatMessage;
import kr.astra.chat.entity.ChatRole;
import org.junit.jupiter.api.Test;

class ChatMessageResponseTest {

    @Test
    void 프롬프트와_원본_AI_응답은_DB_에만_두고_API_응답에서는_뺀다() {
        Map<String, Object> stored = Map.of(
                "extractedConditions", List.of(Map.of("key", "sample_mass")),
                "suggestedTestType", "VIBRATION",
                "prompt", "시스템 프롬프트", "promptInput", "{...}", "rawResponse", "원본",
                "conversation", Map.of("prompt", "상담 프롬프트", "promptInput", "{...}", "rawResponse", "원본",
                        "model", "gemini", "outputTokens", 119));
        var message = new ChatMessage(1L, ChatRole.ASSISTANT, "답변", stored);

        Map<String, Object> exposed = ChatResponse.Message.of(message).metadata();

        assertThat(exposed).containsKeys("extractedConditions", "suggestedTestType", "conversation")
                .doesNotContainKeys("prompt", "promptInput", "rawResponse");
        @SuppressWarnings("unchecked")
        Map<String, Object> conversation = (Map<String, Object>) exposed.get("conversation");
        assertThat(conversation).containsKeys("model", "outputTokens")
                .doesNotContainKeys("prompt", "promptInput", "rawResponse");
        // 저장된 원본(감사용)은 그대로
        assertThat(message.getMetadata()).containsKey("promptInput");
    }

    @Test
    void 메타데이터가_없는_메시지도_그대로_응답한다() {
        assertThat(ChatResponse.Message.of(new ChatMessage(1L, ChatRole.USER, "질문", null)).metadata()).isNull();
    }
}
