package kr.astra.chat.dto;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import kr.astra.chat.entity.*;
public final class ChatResponse {
    private ChatResponse() {}
    public record Session(Long id, String title, ChatSessionStatus status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public static Session of(ChatSession s) { return new Session(s.getId(),s.getTitle(),s.getStatus(),s.getCreatedAt(),s.getUpdatedAt()); }
    }
    public record Message(Long id, ChatRole role, String content, Map<String,Object> metadata, OffsetDateTime createdAt) {
        /** 프롬프트·AI 원본 응답은 감사용으로 DB(chat_messages.metadata)에만 두고 API 응답에서는 뺌 */
        private static final Set<String> INTERNAL = Set.of("prompt","promptInput","rawResponse");
        public static Message of(ChatMessage m) { return new Message(m.getId(),m.getRole(),m.getContent(),visible(m.getMetadata()),m.getCreatedAt()); }
        private static Map<String,Object> visible(Map<String,Object> metadata) {
            if (metadata == null) return null;
            Map<String,Object> result = new LinkedHashMap<>();
            metadata.forEach((key,value) -> {
                if (INTERNAL.contains(key)) return;
                result.put(key, value instanceof Map<?,?> nested ? visibleNested(nested) : value);
            });
            return result;
        }
        private static Map<Object,Object> visibleNested(Map<?,?> nested) {
            Map<Object,Object> result = new LinkedHashMap<>();
            nested.forEach((key,value) -> { if (!INTERNAL.contains(key)) result.put(key,value); });
            return result;
        }
    }
    public record Exchange(Message userMessage, Message assistantMessage) {}
}
