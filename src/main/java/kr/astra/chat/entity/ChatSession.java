package kr.astra.chat.entity;
import jakarta.persistence.*;
import kr.astra.global.entity.BaseTimeEntity;
import lombok.*;
@Getter @Entity @Table(name = "chat_sessions") @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatSession extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long userId;
    @Column(length = 200) private String title;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ChatSessionStatus status = ChatSessionStatus.ACTIVE;
    public ChatSession(Long owner, String title) { userId = owner; this.title = title; }
}
