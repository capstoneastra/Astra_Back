package kr.astra.notification.service;

import kr.astra.notification.entity.NotificationType;

/**
 * [BE-B 제공 → 전원 사용] 알림 생성. 구현: BE-B (team-guide.md §4 내부 인터페이스)
 * 의뢰 상태 변경과 같은 트랜잭션 안에서 호출합니다 (api-spec.md §11).
 */
public interface NotificationService {

    /**
     * @param userId           받는 사람 users.id
     * @param type             알림 종류 (api-spec.md §13)
     * @param title            알림 제목
     * @param linkPath         클릭 시 이동할 프론트 경로 (예: /requests/12)
     * @param relatedRequestId 관련 의뢰 id (없으면 null)
     */
    void notify(Long userId, NotificationType type, String title, String linkPath, Long relatedRequestId);
}
