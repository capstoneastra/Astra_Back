package kr.astra.global.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import kr.astra.global.exception.ErrorCode;
import org.springframework.http.MediaType;

/** 필터 단계 에러는 컨트롤러 밖이라 GlobalExceptionHandler 가 못 잡으므로 같은 ApiResponse 형식으로 직접 씁니다. */
public final class FilterErrorWriter {

    private FilterErrorWriter() {
    }

    public static void write(HttpServletResponse res, ErrorCode code) throws IOException {
        res.setStatus(code.status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"success\":false,\"error\":{\"code\":\"" + code.name()
                + "\",\"message\":\"" + code.defaultMessage() + "\",\"details\":null}}");
    }
}
