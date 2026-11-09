package kr.astra.llm.client;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import kr.astra.global.exception.*;
import org.springframework.stereotype.Component;
/** Bounded background work; no common pool or unbounded per-request thread creation. */
@Component
public class AiTaskExecutor {
    private final AtomicInteger sequence = new AtomicInteger();
    private final ExecutorService executor = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                Thread t = new Thread(task, "astra-ai-" + sequence.incrementAndGet());
                t.setDaemon(true); return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    public Future<?> submit(Runnable task) {
        try { return executor.submit(task); }
        catch (RejectedExecutionException e) { throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS); }
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
