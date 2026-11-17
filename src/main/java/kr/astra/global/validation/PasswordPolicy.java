package kr.astra.global.validation;

import java.nio.charset.StandardCharsets;

/** BCrypt 는 UTF-8 72바이트까지만 받습니다 (넘으면 encode 가 예외). 글자 수 검사만으로는 한글 비밀번호가 빠져나감 */
public final class PasswordPolicy {

    public static final int BCRYPT_MAX_BYTES = 72;
    public static final String TOO_LONG_MESSAGE = "비밀번호가 너무 깁니다. 72바이트 이하로 입력해 주세요. (한글은 1자에 3바이트)";

    private PasswordPolicy() {
    }

    public static boolean withinBcryptLimit(String rawPassword) {
        return rawPassword == null || rawPassword.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
    }
}
