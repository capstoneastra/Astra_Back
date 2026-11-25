package kr.astra.auth.service;

import java.time.OffsetDateTime;
import kr.astra.auth.repository.EmailVerificationRepository;
import kr.astra.auth.repository.PasswordResetRepository;
import kr.astra.user.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 만료된 인증 기록을 매일 삭제해 테이블이 계속 커지는 것을 막습니다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredAuthRecordCleanup {

    private final RefreshTokenRepository refreshTokens;
    private final EmailVerificationRepository emailVerifications;
    private final PasswordResetRepository passwordResets;

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    @Transactional
    public void cleanup() {
        OffsetDateTime now = OffsetDateTime.now();
        int tokens = refreshTokens.deleteExpired(now);
        int verifications = emailVerifications.deleteExpired(now);
        int resets = passwordResets.deleteExpired(now);
        log.info("만료된 인증 기록 정리: refresh 토큰 {}건, 이메일 인증 {}건, 비밀번호 재설정 {}건", tokens, verifications, resets);
    }
}
