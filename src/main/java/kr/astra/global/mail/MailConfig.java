package kr.astra.global.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

/**
 * 메일 발송 구현 선택.
 * MAIL_HOST(spring.mail.host)가 있으면 SMTP 로 보내고, 비어 있으면 서버 로그로 대신 출력합니다.
 */
@Slf4j
@Configuration
public class MailConfig {

    @Bean
    public EmailSender emailSender(ObjectProvider<JavaMailSender> javaMailSender,
                                   @Value("${spring.mail.host:}") String host,
                                   @Value("${spring.mail.username:}") String username,
                                   @Value("${astra.mail.from:}") String configuredFrom) {
        JavaMailSender smtp = javaMailSender.getIfAvailable();
        if (!StringUtils.hasText(host) || smtp == null) {
            log.info("MAIL_HOST 가 없어 메일을 보내지 않고 서버 로그에 출력합니다.");
            return (to, subject, text) -> log.info("[메일 미발송: 로그 출력] to={} subject={}\n{}", to, subject, text);
        }
        // .env 에 MAIL_FROM= 처럼 빈 값이면 SMTP 계정(MAIL_USERNAME)을 보낸 사람으로 사용
        String from = StringUtils.hasText(configuredFrom) ? configuredFrom
                : StringUtils.hasText(username) ? username : "no-reply@astra.local";
        return (to, subject, text) -> {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            smtp.send(message);
        };
    }
}
