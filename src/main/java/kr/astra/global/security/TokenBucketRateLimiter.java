package kr.astra.global.security;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 키(IP 등)별 토큰 버킷. period 동안 capacity 번까지 허용하고, 토큰은 일정한 속도로 다시 찹니다.
 * 서버가 한 대라 메모리로 충분합니다. 키가 maxKeys 를 넘으면 다 찬(오래 안 쓴) 버킷부터 지워 메모리를 제한합니다.
 */
public class TokenBucketRateLimiter {

    private final int capacity;
    private final double nanosPerToken;
    private final int maxKeys;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private record Bucket(double tokens, long updatedAtNanos) {
    }

    public TokenBucketRateLimiter(int capacity, Duration period, int maxKeys, LongSupplier nanoClock) {
        if (capacity <= 0 || period.isNegative() || period.isZero() || maxKeys <= 0) {
            throw new IllegalArgumentException("capacity·period·maxKeys 는 0보다 커야 합니다.");
        }
        this.capacity = capacity;
        this.nanosPerToken = (double) period.toNanos() / capacity;
        this.maxKeys = maxKeys;
        this.nanoClock = nanoClock;
    }

    /** @return 0 이면 허용(토큰 1개 사용), 아니면 다시 시도할 수 있을 때까지 남은 초 (1 이상) */
    public long tryAcquire(String key) {
        if (buckets.size() >= maxKeys && !buckets.containsKey(key)) {
            evictFull();
        }
        long[] waitNanos = {0};
        buckets.compute(key, (k, bucket) -> {
            long now = nanoClock.getAsLong();
            double tokens = refill(bucket, now);
            if (tokens >= 1) {
                return new Bucket(tokens - 1, now);
            }
            waitNanos[0] = (long) Math.ceil((1 - tokens) * nanosPerToken);
            return new Bucket(tokens, now);
        });
        return waitNanos[0] == 0 ? 0 : Math.max(1, (waitNanos[0] + 999_999_999L) / 1_000_000_000L); // 초 단위 올림
    }

    /** 현재 기억하고 있는 키 수 (테스트·모니터링용) */
    public int size() {
        return buckets.size();
    }

    private double refill(Bucket bucket, long now) {
        if (bucket == null) {
            return capacity;
        }
        return Math.min(capacity, bucket.tokens() + (now - bucket.updatedAtNanos()) / nanosPerToken);
    }

    /** 다시 가득 찬 버킷은 없는 것과 같으므로 지움. 그래도 넘치면(동시에 많은 키가 사용 중) 전부 비움 */
    private void evictFull() {
        long now = nanoClock.getAsLong();
        buckets.entrySet().removeIf(e -> refill(e.getValue(), now) >= capacity);
        if (buckets.size() >= maxKeys) {
            buckets.clear();
        }
    }
}
