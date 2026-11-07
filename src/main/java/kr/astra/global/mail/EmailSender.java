package kr.astra.global.mail;

/**
 * 메일 발송. 구현은 {@link MailConfig} 가 고릅니다.
 * <ul>
 *   <li>SMTP 설정(SPRING_MAIL_HOST 등)이 있으면 실제 메일 발송</li>
 *   <li>없으면 서버 로그에 내용을 출력 (로컬 개발·시연용)</li>
 * </ul>
 */
public interface EmailSender {

    /**
     * @param to      받는 사람 이메일
     * @param subject 제목
     * @param text    본문 (일반 텍스트)
     */
    void send(String to, String subject, String text);
}
