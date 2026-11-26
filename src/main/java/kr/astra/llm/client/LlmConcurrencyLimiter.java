package kr.astra.llm.client;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

/**
 * 서버 전체의 LLM 동시 호출 수 제한. 공급자는 하나만 등록되므로(astra.ai.provider) 활성 클라이언트가 가진 이 객체가 곧 전역 제한입니다.
 * 허용 수를 기다려도 비지 않으면 공급자 한도를 두드리지 않고 429 로 돌려줍니다.
 */
@Slf4j
public final class LlmConcurrencyLimiter {
    private final Semaphore permits;
    private final Duration wait;

    public LlmConcurrencyLimiter(int permits, Duration wait) {
        this.permits = new Semaphore(Math.max(0, permits), true);
        this.wait = wait;
    }

    public <T> T call(Supplier<T> action) {
        boolean acquired;
        // 상담처럼 전체 마감이 있으면 그 안에서만 기다림
        long waitMillis = LlmDeadline.remaining(wait).toMillis();
        try { acquired = permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }
        if (!acquired) {
            log.warn("LLM 동시 호출 한도 초과로 요청을 거절합니다 (대기 {}ms)", waitMillis);
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "AI 요청이 몰려 있습니다. 잠시 후 다시 시도해 주세요.", null);
        }
        try { return action.get(); }
        finally { permits.release(); }
    }
}
