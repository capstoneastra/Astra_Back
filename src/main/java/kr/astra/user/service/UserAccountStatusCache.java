package kr.astra.user.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import kr.astra.global.security.AccountStatusChecker;
import kr.astra.user.entity.UserStatus;
import kr.astra.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 계정 상태 확인 (AccountStatusChecker 구현). 요청마다 DB 를 보지 않도록 30초 동안 기억합니다.
 * 관리자가 승인·거절·정지·해제하면 UserService 가 {@link #evictAfterCommit(Long)} 로 바로 지워서
 * 상태 변경은 다음 요청부터 즉시 반영됩니다 (서버가 한 대라 메모리 캐시로 충분).
 */
@Component
@RequiredArgsConstructor
public class UserAccountStatusCache implements AccountStatusChecker {

    static final Duration TTL = Duration.ofSeconds(30);

    private final UserRepository userRepository;
    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(Status status, long expiresAtNanos) {
    }

    @Override
    public Status check(Long userId) {
        if (userId == null) {
            return Status.NOT_FOUND;
        }
        long now = System.nanoTime();
        Entry entry = cache.get(userId);
        if (entry != null && now - entry.expiresAtNanos() < 0) {
            return entry.status();
        }
        Status status = userRepository.findStatusById(userId)
                .map(s -> s == UserStatus.ACTIVE ? Status.ACTIVE : Status.NOT_ACTIVE)
                .orElse(Status.NOT_FOUND);
        cache.put(userId, new Entry(status, now + TTL.toNanos()));
        return status;
    }

    /**
     * 상태가 바뀐 계정의 캐시를 지웁니다. 트랜잭션 안이면 커밋이 끝난 뒤에 지움
     * (커밋 전에 지우면 그 사이 다른 요청이 옛 상태를 다시 30초 동안 기억할 수 있음).
     */
    public void evictAfterCommit(Long userId) {
        cache.remove(userId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.remove(userId);
                }
            });
        }
    }
}
