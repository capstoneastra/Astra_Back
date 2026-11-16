package kr.astra.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import kr.astra.auth.dto.EmailVerificationConfirmResponse;
import kr.astra.auth.dto.EmailVerificationSentResponse;
import kr.astra.auth.entity.EmailVerification;
import kr.astra.auth.repository.EmailVerificationRepository;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.mail.EmailSender;
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
 * 회원가입 이메일 인증 (api-spec §1).
 * <ol>
 *   <li>코드 발송: 6자리 숫자 코드를 메일로 보냄 (10분 유효, 같은 이메일은 60초에 한 번)</li>
 *   <li>코드 확인: 맞으면 가입용 verificationToken 발급 (30분 유효). 5번 틀리면 코드를 다시 받아야 함</li>
 *   <li>가입: AuthService 가 {@link #consumeToken} 으로 토큰을 한 번 사용 처리</li>
 * </ol>
 */
@Slf4j
@Service
@Transactional
public class EmailVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailVerificationRepository repository;
    private final UserService userService;
    private final EmailSender emailSender;
    private final Duration codeValidity;
    private final Duration tokenValidity;
    private final Duration resendInterval;
    private final int maxAttempts;
    private final boolean logCode;
    private final TransactionTemplate transactions;

    public EmailVerificationService(EmailVerificationRepository repository, UserService userService,
                                    EmailSender emailSender,
                                    @Value("${astra.email-verification.code-validity-seconds}") long codeValiditySeconds,
                                    @Value("${astra.email-verification.token-validity-seconds}") long tokenValiditySeconds,
                                    @Value("${astra.email-verification.resend-interval-seconds}") long resendIntervalSeconds,
                                    @Value("${astra.email-verification.max-attempts}") int maxAttempts,
                                    @Value("${astra.mail.log-code:false}") boolean logCode,
                                    PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.userService = userService;
        this.emailSender = emailSender;
        this.codeValidity = Duration.ofSeconds(codeValiditySeconds);
        this.tokenValidity = Duration.ofSeconds(tokenValiditySeconds);
        this.resendInterval = Duration.ofSeconds(resendIntervalSeconds);
        this.maxAttempts = maxAttempts;
        this.logCode = logCode;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 인증 코드 발송. 이미 가입된 이메일이면 409 EMAIL_ALREADY_EXISTS,
     * 60초 안에 다시 요청하면 429 TOO_MANY_REQUESTS (details.retryAfter 초).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public EmailVerificationSentResponse sendCode(String rawEmail) {
        String email = UserService.normalizeEmail(rawEmail);
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        EmailVerification verification = transactions.execute(status -> {
            userService.checkEmailAvailable(email);
            OffsetDateTime now = OffsetDateTime.now();
            repository.findFirstByEmailOrderByIdDesc(email).ifPresent(last -> {
                long wait = Duration.between(now, last.getCreatedAt().plus(resendInterval)).toSeconds();
                if (wait > 0) {
                    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                            "인증 코드는 %d초 후에 다시 받을 수 있습니다.".formatted(wait), Map.of("retryAfter", wait));
                }
            });
            return repository.saveAndFlush(new EmailVerification(email, sha256(code), now.plus(codeValidity)));
        });
        try {
            emailSender.send(email, "[ASTRA] 회원가입 인증 코드", """
                    ASTRA 회원가입 인증 코드: %s

                    %d분 안에 가입 화면에 입력해 주세요.
                    본인이 요청하지 않았다면 이 메일을 무시해 주세요.
                    """.formatted(code, codeValidity.toMinutes()));
        } catch (MailException e) {
            log.error("인증 메일 발송 실패: {}", email, e);
            try {
                transactions.executeWithoutResult(status -> repository.deleteById(verification.getId()));
            } catch (RuntimeException cleanupError) {
                log.error("발송 실패한 인증 코드 정리 실패: {}", email, cleanupError);
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.", null);
        }
        if (logCode) {
            // 시연 대비용 (MAIL_LOG_CODE=true 일 때만). 평소에는 끄기: 로그를 보는 사람이 남의 이메일로 인증할 수 있음
            log.warn("[인증 코드] to={} code={}", email, code);
        }
        return new EmailVerificationSentResponse(codeValidity.toSeconds(), resendInterval.toSeconds());
    }

    /**
     * 코드 확인. 가장 최근에 보낸 코드만 유효합니다.
     * <ul>
     *   <li>틀림 → 400 VERIFICATION_CODE_INVALID (details.remainingAttempts)</li>
     *   <li>만료·5회 초과·보낸 적 없음·이미 확인됨 → 400 VERIFICATION_CODE_EXPIRED (다시 받기)</li>
     * </ul>
     * 틀린 횟수는 예외가 나도 저장되어야 하므로 BusinessException 으로 롤백하지 않습니다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public EmailVerificationConfirmResponse confirm(String rawEmail, String code) {
        String email = UserService.normalizeEmail(rawEmail);
        EmailVerification verification = repository.findFirstByEmailOrderByIdDesc(email)
                .filter(v -> !v.isVerified())
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_CODE_EXPIRED));
        OffsetDateTime now = OffsetDateTime.now();
        if (verification.isCodeExpired(now) || verification.getAttemptCount() >= maxAttempts) {
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_EXPIRED);
        }
        if (!MessageDigest.isEqual(verification.getCodeHash().getBytes(StandardCharsets.US_ASCII),
                sha256(code).getBytes(StandardCharsets.US_ASCII))) {
            int remaining = Math.max(0, maxAttempts - verification.recordFailedAttempt());
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID,
                    ErrorCode.VERIFICATION_CODE_INVALID.defaultMessage(), Map.of("remainingAttempts", remaining));
        }
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        verification.markVerified(now, sha256(token), now.plus(tokenValidity));
        return new EmailVerificationConfirmResponse(token, tokenValidity.toSeconds());
    }

    /**
     * 가입 시 인증 토큰을 한 번 사용 처리합니다. 가입 트랜잭션 안에서 호출하므로 가입이 실패하면 함께 되돌아갑니다.
     * 토큰이 없거나, 다른 이메일의 토큰이거나, 이미 썼거나 만료됐으면 400 EMAIL_NOT_VERIFIED.
     */
    public void consumeToken(String rawEmail, String token) {
        String email = UserService.normalizeEmail(rawEmail);
        OffsetDateTime now = OffsetDateTime.now();
        EmailVerification verification = repository.findByTokenHash(sha256(token))
                .filter(v -> v.isTokenUsable(email, now))
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED,
                        ErrorCode.EMAIL_NOT_VERIFIED.defaultMessage(),
                        Map.of("fields", List.of(Map.of("field", "verificationToken",
                                "reason", "인증되지 않았거나 만료된 토큰입니다. 이메일 인증을 다시 해 주세요.")))));
        verification.markUsed(now);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다.", e);
        }
    }
}
