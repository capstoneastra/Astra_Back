package kr.astra.notification.service;

import java.time.OffsetDateTime;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.response.PageResponse;
import kr.astra.global.security.AccessGuard;
import kr.astra.global.security.CurrentUser;
import kr.astra.notification.dto.NotificationResponse;
import kr.astra.notification.entity.Notification;
import kr.astra.notification.entity.NotificationType;
import kr.astra.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 생성(NotificationService 구현, 전원 사용) + 내 알림 조회·읽음 (api-spec §10).
 * notify 는 호출한 쪽 트랜잭션에 참여합니다 — 의뢰 상태 변경이 롤백되면 알림도 남지 않음.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationServiceImpl implements NotificationService {

    private static final int TITLE_MAX = 200;
    private static final int LINK_MAX = 300;

    private final NotificationRepository notificationRepository;

    @Override
    public void notify(Long userId, NotificationType type, String title, String linkPath, Long relatedRequestId) {
        if (userId == null || type == null) {
            log.warn("알림 대상이나 종류가 없어 건너뜀: userId={}, type={}", userId, type);
            return;
        }
        notificationRepository.save(new Notification(userId, type, cut(title == null ? type.name() : title, TITLE_MAX),
                null, linkPath == null ? null : cut(linkPath, LINK_MAX), relatedRequestId));
    }

    /** GET /notifications?unreadOnly=true (최신순) */
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(CurrentUser user, boolean unreadOnly, Pageable pageable) {
        Long userId = AccessGuard.requireLogin(user).userId();
        Page<Notification> page = unreadOnly
                ? notificationRepository.findAllByUserIdAndReadAtIsNull(userId, pageable)
                : notificationRepository.findAllByUserId(userId, pageable);
        return PageResponse.from(page, NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public NotificationResponse.UnreadCount unreadCount(CurrentUser user) {
        return new NotificationResponse.UnreadCount(
                notificationRepository.countByUserIdAndReadAtIsNull(AccessGuard.requireLogin(user).userId()));
    }

    /** PATCH /notifications/{id}/read — 본인 알림만 (관리자도 남의 알림은 읽음 처리하지 않음) */
    public NotificationResponse markRead(CurrentUser user, Long id) {
        Long userId = AccessGuard.requireLogin(user).userId();
        Notification n = notificationRepository.findById(id)
                .filter(found -> found.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "알림을 찾을 수 없습니다.", null));
        n.markRead(OffsetDateTime.now());
        return NotificationResponse.from(n);
    }

    /** POST /notifications/read-all */
    public NotificationResponse.ReadAll markAllRead(CurrentUser user) {
        return new NotificationResponse.ReadAll(
                notificationRepository.markAllRead(AccessGuard.requireLogin(user).userId(), OffsetDateTime.now()));
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
