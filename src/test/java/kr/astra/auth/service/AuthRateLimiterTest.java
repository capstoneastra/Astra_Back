package kr.astra.auth.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class AuthRateLimiterTest {

    @Test
    void 인증_메일은_IP당_한도를_넘으면_429() {
        AuthRateLimiter limiter = new AuthRateLimiter(5, 60, 20);
        for (int i = 0; i < 5; i++) {
            limiter.checkEmailVerification("10.0.0.1");
        }
        assertTooMany(() -> limiter.checkEmailVerification("10.0.0.1"));
        // 다른 IP 는 아직 됨
        assertThatCode(() -> limiter.checkEmailVerification("10.0.0.2")).doesNotThrowAnyException();
    }

    @Test
    void 인증_메일은_IP를_바꿔도_전체_한도를_넘으면_429() {
        AuthRateLimiter limiter = new AuthRateLimiter(5, 10, 20);
        for (int i = 0; i < 10; i++) {
            limiter.checkEmailVerification("10.1.0." + i);
        }
        assertTooMany(() -> limiter.checkEmailVerification("10.1.0.99"));
    }

    @Test
    void 로그인은_IP당_한도를_넘으면_429() {
        AuthRateLimiter limiter = new AuthRateLimiter(5, 60, 20);
        for (int i = 0; i < 20; i++) {
            limiter.checkLogin("10.2.0.1");
        }
        assertTooMany(() -> limiter.checkLogin("10.2.0.1"));
        // 로그인 한도와 인증 메일 한도는 따로
        assertThatCode(() -> limiter.checkEmailVerification("10.2.0.1")).doesNotThrowAnyException();
    }

    private static void assertTooMany(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    org.assertj.core.api.Assertions.assertThat(be.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
                    org.assertj.core.api.Assertions.assertThat(be.getDetails().toString()).contains("retryAfter");
                });
    }
}
