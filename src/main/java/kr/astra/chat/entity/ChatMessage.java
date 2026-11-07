package kr.astra.chat.entity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Getter @Entity @Table(name = "chat_messages") @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long sessionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ChatRole role;
    @Column(nullable = false, columnDefinition = "text") private String content;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private Map<String,Object> metadata;
    @CreationTimestamp @Column(nullable = false, updatable = false) private OffsetDateTime createdAt;
    public ChatMessage(Long sessionId, ChatRole role, String content, Map<String,Object> metadata) {
        this.sessionId = sessionId; this.role = role; this.content = content; this.metadata = metadata;
    }
    public void mark(String state) { metadata = Map.of("state", state); }
}
