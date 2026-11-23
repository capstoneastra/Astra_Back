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
    RESULT_REGISTERED,
    /** 의뢰자가 제출한 뒤의 의뢰를 취소 → 장비 보유 조직 구성원 전원 */
    REQUEST_CANCELED,
    /** 확정 전 일정 제안이 취소(거절)됨 → 상대편 */
    RESERVATION_CANCELED,
    /** 장비 보유 조직이 예상 이용료를 적거나 바꿈 → 의뢰자 */
    ESTIMATED_FEE_UPDATED,
    AI_RUN_DONE,
    AI_RUN_WAITING,
    USER_APPROVAL_REQUESTED,
    USER_APPROVED,
    USER_REJECTED,
    EQUIPMENT_UPDATE_REQUIRED
}
