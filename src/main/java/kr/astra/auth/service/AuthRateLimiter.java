package kr.astra.auth.service;

import java.time.Duration;
import java.util.Map;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.security.TokenBucketRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 공개 인증 API 남용 방지 (분당 횟수, 메모리). 넘으면 429 TOO_MANY_REQUESTS (details.retryAfter 초).
 * <ul>
 *   <li>인증 메일 발송: IP 당 + 서버 전체 (여러 주소로 메일을 뿌려 발송 한도를 소진하는 것 방지)</li>
 *   <li>로그인: IP 당 (비밀번호 대입 방지)</li>
 * </ul>
 * IP 는 request.getRemoteAddr() 기준 (프록시 헤더 설정이 없으므로 X-Forwarded-For 는 믿지 않음).
 */
@Component
public class AuthRateLimiter {

    private static final Duration PERIOD = Duration.ofMinutes(1);
    private static final int MAX_KEYS = 10_000;
    private static final String GLOBAL_KEY = "*";

    private final TokenBucketRateLimiter emailPerIp;
    private final TokenBucketRateLimiter emailGlobal;
    private final TokenBucketRateLimiter loginPerIp;

    public AuthRateLimiter(
            @Value("${astra.rate-limit.email-verification-per-ip-per-minute:5}") int emailPerIpPerMinute,
            @Value("${astra.rate-limit.email-verification-global-per-minute:60}") int emailGlobalPerMinute,
            @Value("${astra.rate-limit.login-per-ip-per-minute:20}") int loginPerIpPerMinute) {
        this.emailPerIp = new TokenBucketRateLimiter(emailPerIpPerMinute, PERIOD, MAX_KEYS, System::nanoTime);
        this.emailGlobal = new TokenBucketRateLimiter(emailGlobalPerMinute, PERIOD, 1, System::nanoTime);
        this.loginPerIp = new TokenBucketRateLimiter(loginPerIpPerMinute, PERIOD, MAX_KEYS, System::nanoTime);
    }

    public void checkEmailVerification(String clientIp) {
        check(emailPerIp.tryAcquire(clientIp));
        check(emailGlobal.tryAcquire(GLOBAL_KEY));
    }

    public void checkLogin(String clientIp) {
        check(loginPerIp.tryAcquire(clientIp));
    }

    private static void check(long retryAfter) {
        if (retryAfter > 0) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "요청이 너무 잦습니다. %d초 후에 다시 시도해 주세요.".formatted(retryAfter),
                    Map.of("retryAfter", retryAfter));
        }
    }
}
