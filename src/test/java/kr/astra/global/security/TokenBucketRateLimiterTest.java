package kr.astra.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    private final AtomicLong clock = new AtomicLong();

    @Test
    void 용량만큼_허용하고_그다음은_대기_시간을_알려준다() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, Duration.ofMinutes(1), 100, clock::get);
        assertThat(limiter.tryAcquire("1.1.1.1")).isZero();
        assertThat(limiter.tryAcquire("1.1.1.1")).isZero();
        assertThat(limiter.tryAcquire("1.1.1.1")).isZero();
        long wait = limiter.tryAcquire("1.1.1.1");
        assertThat(wait).isBetween(1L, 20L); // 3개/60초 → 20초마다 1개 충전
        // 다른 키는 따로 셈
        assertThat(limiter.tryAcquire("2.2.2.2")).isZero();
    }

    @Test
    void 시간이_지나면_다시_충전된다() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, Duration.ofMinutes(1), 100, clock::get);
        limiter.tryAcquire("k");
        limiter.tryAcquire("k");
        assertThat(limiter.tryAcquire("k")).isPositive();
        clock.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThat(limiter.tryAcquire("k")).isZero();
        assertThat(limiter.tryAcquire("k")).isPositive();
    }

    @Test
    void 키_개수가_상한을_넘으면_다_찬_버킷부터_정리해_메모리가_계속_늘지_않는다() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, Duration.ofMinutes(1), 10, clock::get);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("ip-" + i);
        }
        clock.addAndGet(Duration.ofMinutes(2).toNanos()); // 모두 다시 가득 참
        for (int i = 10; i < 1000; i++) {
            limiter.tryAcquire("ip-" + i);
        }
        assertThat(limiter.size()).isLessThanOrEqualTo(10);
    }

    @Test
    void 동시에_호출해도_용량보다_많이_허용하지_않는다() throws Exception {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(50, Duration.ofHours(1), 100, System::nanoTime);
        AtomicLong allowed = new AtomicLong();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    if (limiter.tryAcquire("same") == 0) {
                        allowed.incrementAndGet();
                    }
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(allowed.get()).isEqualTo(50);
    }
}
