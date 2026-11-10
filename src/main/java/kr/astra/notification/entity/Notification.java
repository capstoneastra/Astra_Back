package kr.astra.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** notifications: 사용자 알림 (api-spec §13 트리거). read_at 이 null 이면 안 읽음 */
@Getter
@Entity
@Table(name = "notifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String body;

    /** 프론트 라우트 (예: /requests/12) */
    @Column(name = "link_path", length = 300)
    private String linkPath;

    @Column(name = "related_request_id")
    private Long relatedRequestId;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Notification(Long userId, NotificationType type, String title, String body, String linkPath,
                        Long relatedRequestId) {
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.linkPath = linkPath;
        this.relatedRequestId = relatedRequestId;
    }

    public void markRead(OffsetDateTime now) {
        if (readAt == null) {
            readAt = now;
        }
    }
}
