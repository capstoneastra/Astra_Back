package kr.astra.notification.dto;

import java.time.OffsetDateTime;
import kr.astra.notification.entity.Notification;
import kr.astra.notification.entity.NotificationType;

/** GET /notifications 한 줄 (api-spec §10) */
public record NotificationResponse(
        Long id,
        NotificationType type,
        String title,
        String body,
        String linkPath,
        Long relatedRequestId,
        boolean isRead,
        OffsetDateTime readAt,
        OffsetDateTime createdAt) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getLinkPath(),
                n.getRelatedRequestId(), n.getReadAt() != null, n.getReadAt(), n.getCreatedAt());
    }

    /** GET /notifications/unread-count */
    public record UnreadCount(long count) {
    }

    /** POST /notifications/read-all */
    public record ReadAll(int updated) {
    }
}
