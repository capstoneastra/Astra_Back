package kr.astra.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import kr.astra.auth.dto.AccountRecoveryDtos.FindEmailResponse;
import kr.astra.auth.dto.AccountRecoveryDtos.PasswordResetConfirmResponse;
import kr.astra.auth.dto.EmailVerificationSentResponse;
import kr.astra.auth.entity.PasswordReset;
import kr.astra.auth.repository.PasswordResetRepository;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.mail.EmailSender;
import kr.astra.global.security.Hashes;
import kr.astra.user.service.RefreshTokenService;
import kr.astra.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계정 찾기 (api-spec §1-2). 모두 로그인 없이 호출하므로 계정이 있는지 드러내지 않습니다.
 * <ul>
 *   <li>이메일 찾기: 이름·연락처(숫자만)가 둘 다 맞는 회원의 이메일을 가려서 돌려줌. 없으면 빈 배열</li>
 *   <li>재설정 코드 발송: 가입 여부와 관계없이 같은 200. 가입한 이메일에만 실제로 메일을 보냄.
 *       코드 규칙은 회원가입 인증과 같음 (6자리, 10분, 60초에 한 번, 5번 틀리면 다시 받기)</li>
 *   <li>코드 확인: 맞으면 재설정 토큰(15분, 한 번만) 발급</li>
 *   <li>새 비밀번호 저장: 토큰을 사용 처리하고 그 회원의 refresh 토큰을 모두 폐기 (다른 기기 로그아웃)</li>
 * </ul>
 * 승인 대기·반려 계정도 찾기·재설정은 됩니다. 로그인 가능 여부는 기존 규칙 그대로입니다.
 */
@Slf4j
@Service
@Transactional
public class AccountRecoveryService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordResetRepository repository;
    private final UserService userService;
    private final RefreshTokenService refreshTokenService;
    private final EmailSender emailSender;
    private final Duration codeValidity;
    private final Duration tokenValidity;
    private final Duration resendInterval;
    private final int maxAttempts;
    private final boolean logCode;
    private final TransactionTemplate transactions;

    public AccountRecoveryService(PasswordResetRepository repository, UserService userService,
                                  RefreshTokenService refreshTokenService, EmailSender emailSender,
                                  @Value("${astra.email-verification.code-validity-seconds}") long codeValiditySeconds,
                                  @Value("${astra.password-reset.token-validity-seconds:900}") long tokenValiditySeconds,
                                  @Value("${astra.email-verification.resend-interval-seconds}") long resendIntervalSeconds,
                                  @Value("${astra.email-verification.max-attempts}") int maxAttempts,
                                  @Value("${astra.mail.log-code:false}") boolean logCode,
                                  PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.userService = userService;
        this.refreshTokenService = refreshTokenService;
        this.emailSender = emailSender;
        this.codeValidity = Duration.ofSeconds(codeValiditySeconds);
        this.tokenValidity = Duration.ofSeconds(tokenValiditySeconds);
        this.resendInterval = Duration.ofSeconds(resendIntervalSeconds);
        this.maxAttempts = maxAttempts;
        this.logCode = logCode;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------ 이메일 찾기

    @Transactional(readOnly = true)
    public FindEmailResponse findEmails(String name, String phone) {
        String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        return new FindEmailResponse(userService.findEmailsByNameAndPhone(name, digits).stream()
                .map(AccountRecoveryService::mask).toList());
    }

    /** 아이디 부분 앞 2글자(3글자 미만이면 1글자)만 보이고 나머지는 *. 도메인은 그대로 (co*****@astra.kr) */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        int keep = local.length() >= 3 ? 2 : 1;
        return local.substring(0, keep) + "*".repeat(Math.max(1, local.length() - keep)) + email.substring(at);
    }

    // ------------------------------------------------------------ 비밀번호 재설정

    /**
     * 재설정 코드 발송. 가입하지 않은 이메일도 같은 응답(메일만 안 보냄).
     * 60초 안에 다시 요청하면 가입 여부와 관계없이 429 TOO_MANY_REQUESTS (details.retryAfter 초).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public EmailVerificationSentResponse sendCode(String rawEmail) {
        String email = UserService.normalizeEmail(rawEmail);
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        PasswordReset reset = transactions.execute(status -> {
            repository.lockEmail(email);
            OffsetDateTime now = OffsetDateTime.now();
            repository.findFirstByEmailOrderByIdDesc(email).ifPresent(last -> {
                long wait = Duration.between(now, last.getCreatedAt().plus(resendInterval)).toSeconds();
                if (wait > 0) {
                    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                            "인증 코드는 %d초 후에 다시 받을 수 있습니다.".formatted(wait), Map.of("retryAfter", wait));
                }
            });
            Long userId = userService.findIdByEmail(email).orElse(null);
            return repository.saveAndFlush(new PasswordReset(email, userId, Hashes.sha256(code),
                    now.plus(codeValidity)));
        });
        if (reset.getUserId() != null) {
            send(reset, email, code);
        }
        return new EmailVerificationSentResponse(codeValidity.toSeconds(), resendInterval.toSeconds());
    }

    private void send(PasswordReset reset, String email, String code) {
        try {
            emailSender.send(email, "[ASTRA] 비밀번호 재설정 인증 코드", """
                    ASTRA 비밀번호 재설정 인증 코드: %s

                    %d분 안에 계정 찾기 화면에 입력해 주세요.
                    본인이 요청하지 않았다면 이 메일을 무시해 주세요. 비밀번호는 바뀌지 않습니다.
                    """.formatted(code, codeValidity.toMinutes()));
        } catch (MailException e) {
            log.error("재설정 메일 발송 실패: {}", email, e);
            try {
                transactions.executeWithoutResult(status -> repository.deleteById(reset.getId()));
            } catch (RuntimeException cleanupError) {
                log.error("발송 실패한 재설정 코드 정리 실패: {}", email, cleanupError);
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.", null);
        }
        if (logCode) {
            log.warn("[재설정 코드] to={} code={}", email, code);
        }
    }

    /**
     * 코드 확인. 가장 최근에 보낸 코드만 유효합니다.
     * 틀림 → 400 VERIFICATION_CODE_INVALID (details.remainingAttempts),
     * 만료·5회 초과·보낸 적 없음·이미 확인됨 → 400 VERIFICATION_CODE_EXPIRED.
     * 틀린 횟수는 예외가 나도 저장되어야 하므로 BusinessException 으로 롤백하지 않습니다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public PasswordResetConfirmResponse confirm(String rawEmail, String code) {
        String email = UserService.normalizeEmail(rawEmail);
        PasswordReset reset = repository.findFirstByEmailOrderByIdDesc(email)
                .filter(r -> !r.isVerified())
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_CODE_EXPIRED));
        OffsetDateTime now = OffsetDateTime.now();
        if (reset.isCodeExpired(now) || reset.getAttemptCount() >= maxAttempts) {
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_EXPIRED);
        }
        if (!MessageDigest.isEqual(reset.getCodeHash().getBytes(StandardCharsets.US_ASCII),
                Hashes.sha256(code).getBytes(StandardCharsets.US_ASCII))) {
            int remaining = Math.max(0, maxAttempts - reset.recordFailedAttempt());
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID,
                    ErrorCode.VERIFICATION_CODE_INVALID.defaultMessage(), Map.of("remainingAttempts", remaining));
        }
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        reset.markVerified(now, Hashes.sha256(token), now.plus(tokenValidity));
        return new PasswordResetConfirmResponse(token, tokenValidity.toSeconds());
    }

    /**
     * 새 비밀번호 저장. 토큰이 없거나 만료·이미 사용이면 400 RESET_TOKEN_INVALID.
     * 성공하면 토큰을 사용 처리하고 그 회원의 refresh 토큰을 모두 폐기합니다 (같은 트랜잭션).
     */
    public void complete(String token, String newPassword) {
        OffsetDateTime now = OffsetDateTime.now();
        Optional<PasswordReset> found = token == null ? Optional.empty()
                : repository.findByTokenHashForUpdate(Hashes.sha256(token));
        PasswordReset reset = found.filter(r -> r.isTokenUsable(now))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESET_TOKEN_INVALID));
        userService.resetPassword(reset.getUserId(), newPassword);
        reset.markUsed(now);
        refreshTokenService.revokeAll(reset.getUserId());
        log.info("비밀번호 재설정 완료: userId={}", reset.getUserId());
    }
}
