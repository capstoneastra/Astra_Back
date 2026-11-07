package kr.astra;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.astra.global.mail.EmailSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 테스트용 메일 발송: 실제로 보내지 않고 이메일별 마지막 인증 코드를 기억합니다.
 * 회원가입 테스트에서 {@code @Import({TestcontainersConfiguration.class, TestMailConfiguration.class})} 로 사용.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestMailConfiguration {

    @Bean
    @Primary
    CapturingEmailSender capturingEmailSender() {
        return new CapturingEmailSender();
    }

    public static class CapturingEmailSender implements EmailSender {

        private static final Pattern CODE = Pattern.compile("인증 코드: (\\d{6})");

        private final Map<String, String> lastCodes = new ConcurrentHashMap<>();

        @Override
        public void send(String to, String subject, String text) {
            Matcher m = CODE.matcher(text);
            if (m.find()) {
                lastCodes.put(to, m.group(1));
            }
        }

        public String lastCode(String email) {
            return lastCodes.get(email.toLowerCase());
        }
    }
}
