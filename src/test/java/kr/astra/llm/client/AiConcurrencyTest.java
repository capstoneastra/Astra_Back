package kr.astra.llm.client;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.*;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import org.junit.jupiter.api.*;

/** 상담 SSE 와 자동 추진이 스레드를 나눠 쓰고, LLM 동시 호출 수가 제한되는지 확인 */
class AiConcurrencyTest {
    final AiTaskExecutor executor = new AiTaskExecutor();
    @AfterEach void close() { executor.close(); }

    @Test void longPipelineRunsDoNotStarveChatStreams() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) executor.submitPipeline(() -> {
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        CountDownLatch chatRan = new CountDownLatch(1);
        executor.submit(chatRan::countDown);
        try { assertThat(chatRan.await(2, TimeUnit.SECONDS)).isTrue(); }
        finally { release.countDown(); }
    }

    @Test void fullPipelineQueueIsTooManyRequests() {
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertThatThrownBy(() -> { for (int i = 0; i < 1000; i++) executor.submitPipeline(() -> {
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }); }).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
        } finally { release.countDown(); }
    }

    @Test void limiterRejectsWithTooManyRequestsWhenPermitsStayBusy() throws Exception {
        var limiter = new LlmConcurrencyLimiter(1, Duration.ofMillis(100));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var holder = CompletableFuture.runAsync(() -> limiter.call(() -> {
            holding.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return null;
        }));
        assertThat(holding.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> limiter.call(() -> "x")).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
            assertThat(e.getMessage()).contains("잠시 후 다시 시도");
        });
        release.countDown(); holder.get(2, TimeUnit.SECONDS);
        assertThat(limiter.call(() -> "ok")).isEqualTo("ok");
    }
}
