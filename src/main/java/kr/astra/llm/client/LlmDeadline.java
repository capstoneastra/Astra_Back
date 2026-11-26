package kr.astra.llm.client;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 한 작업(예: 상담 메시지 하나)에 쓰는 AI 호출 전체의 마감 시각. 같은 스레드의 LLM 클라이언트가 이 값을 보고
 * 모델 하나당 대기 시간을 남은 시간 안으로 줄입니다. 마감이 없으면 클라이언트 기본 시간만 씁니다.
 * 안쪽 {@link #within} 은 바깥 마감보다 늦어지지 않습니다.
 */
public final class LlmDeadline {
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private LlmDeadline() {}

    public static <T> T within(Duration budget, Supplier<T> action) {
        Long outer = DEADLINE.get();
        long candidate = System.nanoTime() + budget.toNanos();
        DEADLINE.set(outer == null || candidate - outer < 0 ? candidate : outer);
        try { return action.get(); }
        finally { if (outer == null) DEADLINE.remove(); else DEADLINE.set(outer); }
    }

    /** 이 스레드에 마감이 걸려 있는지 */
    public static boolean active() { return DEADLINE.get() != null; }

    /** cap 과 남은 마감 시간 중 짧은 쪽. 마감이 지났으면 0 */
    public static Duration remaining(Duration cap) {
        Long deadline = DEADLINE.get();
        if (deadline == null) return cap;
        long left = Math.max(0, deadline - System.nanoTime());
        return left < cap.toNanos() ? Duration.ofNanos(left) : cap;
    }
}
