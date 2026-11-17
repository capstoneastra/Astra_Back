package kr.astra.llm.client;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import kr.astra.global.exception.*;
import org.springframework.stereotype.Component;
/**
 * Bounded background work; no common pool or unbounded per-request thread creation.
 * 상담 SSE(짧고 많음)와 자동 추진(한 건이 수 분)을 다른 풀로 나눠, 자동 추진이 상담 응답 스레드를 다 차지하지 않게 합니다.
 * LLM 공급자 한도는 LlmConcurrencyLimiter 가 따로 제한합니다.
 */
@Component
public class AiTaskExecutor {
    private final ExecutorService chat = pool("astra-ai-chat-", 16, 64);
    private final ExecutorService pipeline = pool("astra-ai-pipeline-", 2, 32);

    private static ExecutorService pool(String prefix, int threads, int queue) {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue), task -> {
            Thread t = new Thread(task, prefix + sequence.incrementAndGet());
            t.setDaemon(true); return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    /** 상담 SSE 응답 생성 */
    public Future<?> submit(Runnable task) { return submit(chat,task); }
    /** AI 자동 추진 실행 */
    public Future<?> submitPipeline(Runnable task) { return submit(pipeline,task); }
    private static Future<?> submit(ExecutorService executor, Runnable task) {
        try { return executor.submit(task); }
        catch (RejectedExecutionException e) { throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS); }
    }
    @PreDestroy public void close() { chat.shutdownNow(); pipeline.shutdownNow(); }
}
