package kr.astra.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

/** CORS_ALLOWED_ORIGINS 값 해석: 쉼표 여러 개, 패턴, 끝의 /, 빈 값 */
class CorsConfigTest {

    @Test
    void 쉼표로_여러_주소와_패턴을_받고_끝의_슬래시는_무시한다() {
        CorsConfiguration config = configOf(" http://localhost:3000/ , https://astra.example.com,http://localhost:* ");
        assertThat(config.checkOrigin("http://localhost:3000")).isEqualTo("http://localhost:3000");
        assertThat(config.checkOrigin("https://astra.example.com")).isEqualTo("https://astra.example.com");
        assertThat(config.checkOrigin("http://localhost:8081")).isEqualTo("http://localhost:8081"); // 패턴
        assertThat(config.checkOrigin("http://evil.example")).isNull();
    }

    @Test
    void 값이_비어_있으면_Vite_기본_주소를_쓴다() {
        // .env.example 을 그대로 복사하면 CORS_ALLOWED_ORIGINS= (빈 값)
        for (String empty : new String[] {"", "  ", ",", null}) {
            CorsConfiguration config = configOf(empty);
            assertThat(config.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
            assertThat(config.checkOrigin("http://localhost:3000")).isNull();
        }
    }

    private static CorsConfiguration configOf(String allowedOrigins) {
        return CorsConfig.source(allowedOrigins).getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/equipment"));
    }
}
