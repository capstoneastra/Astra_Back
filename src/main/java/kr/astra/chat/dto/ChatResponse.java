package kr.astra.chat.dto;
import java.time.OffsetDateTime;
import java.util.Map;
import kr.astra.chat.entity.*;
public final class ChatResponse {
    private ChatResponse() {}
    public record Session(Long id, String title, ChatSessionStatus status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public static Session of(ChatSession s) { return new Session(s.getId(),s.getTitle(),s.getStatus(),s.getCreatedAt(),s.getUpdatedAt()); }
    }
    public record Message(Long id, ChatRole role, String content, Map<String,Object> metadata, OffsetDateTime createdAt) {
        public static Message of(ChatMessage m) { return new Message(m.getId(),m.getRole(),m.getContent(),m.getMetadata(),m.getCreatedAt()); }
    }
    public record Exchange(Message userMessage, Message assistantMessage) {}
}
