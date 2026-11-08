package kr.astra.global.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS: 브라우저에서 프론트(별도 저장소, 다른 주소)가 이 API 를 부를 수 있게 허용합니다.
 * <ul>
 *   <li>허용 주소: 환경변수 CORS_ALLOWED_ORIGINS (쉼표로 여러 개, 기본 http://localhost:5173 = Vite 개발 서버).
 *       포트를 모르면 http://localhost:* 처럼 패턴도 됨. 시연 서버는 프론트 주소를 .env 에 넣으면 됨</li>
 *   <li>인증은 Authorization: Bearer 헤더라 쿠키를 쓰지 않으므로 credentials 는 허용하지 않음</li>
 *   <li>SecurityConfig 의 http.cors() 로 Spring Security 앞단에서 처리되어 401·403 응답에도 CORS 헤더가 붙음
 *       (프론트가 401 을 읽고 로그인 화면으로 보낼 수 있게)</li>
 * </ul>
 * 주소가 틀리면 브라우저 콘솔에 "blocked by CORS policy" 가 뜹니다. Swagger·Postman·서버 간 호출은 CORS 와 무관합니다.
 */
@Configuration
public class CorsConfig {

    /** 값이 없거나 비어 있을 때 쓰는 기본 허용 주소 (Vite 개발 서버) */
    static final String DEFAULT_ORIGIN = "http://localhost:5173";

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${astra.cors.allowed-origins}") String allowedOrigins) {
        return source(allowedOrigins);
    }

    /**
     * 쉼표로 구분된 주소 목록으로 CORS 설정을 만듭니다 (테스트에서도 사용).
     * .env 에 CORS_ALLOWED_ORIGINS= 처럼 빈 값이 들어가도 기본 주소를 쓰도록 여기서 처리합니다
     * (${이름:기본값} 은 빈 문자열일 때 기본값을 쓰지 않음).
     */
    static UrlBasedCorsConfigurationSource source(String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(parse(allowedOrigins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Content-Disposition"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    static List<String> parse(String allowedOrigins) {
        List<String> origins = allowedOrigins == null ? List.of() : Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s)
                .toList();
        return origins.isEmpty() ? List.of(DEFAULT_ORIGIN) : origins;
    }
}
