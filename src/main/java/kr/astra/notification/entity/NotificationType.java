package kr.astra.notification.entity;

/**
 * notifications.type 값 (api-spec.md §13 알림 트리거 표와 1:1)
 */
public enum NotificationType {
    REQUEST_SUBMITTED,
    REQUEST_RESUBMITTED,
    SUPPLEMENT_REQUESTED,
    REQUEST_ACCEPTED,
    REQUEST_REJECTED,
    RESERVATION_PROPOSED,
    RESERVATION_CONFIRMED,
    AI_RUN_DONE,
    AI_RUN_WAITING,
    USER_APPROVAL_REQUESTED,
    USER_APPROVED,
    USER_REJECTED,
    EQUIPMENT_UPDATE_REQUIRED
}
