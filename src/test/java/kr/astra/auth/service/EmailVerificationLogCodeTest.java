package kr.astra.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.astra.auth.entity.EmailVerification;
import kr.astra.auth.repository.EmailVerificationRepository;
import kr.astra.global.mail.EmailSender;
import kr.astra.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** MAIL_LOG_CODE(astra.mail.log-code): 켜져 있을 때만 메일 발송 후 서버 로그에 인증 코드를 남김 */
@ExtendWith(OutputCaptureExtension.class)
class EmailVerificationLogCodeTest {

    private static final Pattern CODE = Pattern.compile("인증 코드: (\\d{6})");

    @Test
    void 켜져_있으면_보낸_코드를_로그에_남긴다(CapturedOutput output) {
        String sent = sendWith(true, "log-on@test.kr");
        assertThat(output).contains("[인증 코드] to=log-on@test.kr code=" + sent);
    }

    @Test
    void 기본값_꺼짐이면_로그에_코드가_없다(CapturedOutput output) {
        String sent = sendWith(false, "log-off@test.kr");
        assertThat(output).doesNotContain("[인증 코드]").doesNotContain(sent);
    }

    /** 서비스로 코드를 보내고, 메일 본문에 들어간 코드를 돌려줌 */
    private static String sendWith(boolean logCode, String email) {
        EmailVerificationRepository repository = mock(EmailVerificationRepository.class);
        when(repository.findFirstByEmailOrderByIdDesc(any())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.<EmailVerification>getArgument(0));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        AtomicReference<String> body = new AtomicReference<>();
        EmailSender sender = (to, subject, text) -> body.set(text);

        new EmailVerificationService(repository, mock(UserService.class), sender, 600, 1800, 60, 5, logCode, transactions)
                .sendCode(email);

        Matcher m = CODE.matcher(body.get());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }
}
