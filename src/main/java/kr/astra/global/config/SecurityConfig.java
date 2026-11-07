package kr.astra.global.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import kr.astra.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 보안 설정 (api-spec.md §0-4).
 * <ul>
 *   <li>비로그인 허용: /api/v1/auth/**, Swagger, 헬스체크</li>
 *   <li>그 외 전부 로그인 필요 → astra.security.require-login=true 일 때 적용</li>
 * </ul>
 * JWT 인증 필터(BE-A 2주차)를 붙이기 전까지는 require-login=false 로 두어 모두가 Swagger 로 API 를 호출해 볼 수 있게 합니다.
 * 필터를 붙인 PR 에서 application.yml 의 값을 true 로 바꾸세요.
 */
@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            WebConfig.API_PREFIX + "/auth/**",
            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
            "/actuator/health"
    };

    @Value("${astra.security.require-login:false}")
    private boolean requireLogin;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(PUBLIC_PATHS).permitAll();
                    if (requireLogin) {
                        auth.anyRequest().authenticated();
                    } else {
                        auth.anyRequest().permitAll();
                    }
                })
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, ErrorCode.AUTH_REQUIRED))
                        .accessDeniedHandler((req, res, ex) -> writeError(res, ErrorCode.FORBIDDEN)));
        return http.build();
    }

    /**
     * JWT 방식이라 아이디/비밀번호 기반 기본 계정은 쓰지 않습니다.
     * 이 빈이 없으면 Spring 이 임시 계정과 비밀번호를 자동 생성합니다.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }

    /** 필터 단계 에러는 컨트롤러 밖이라 GlobalExceptionHandler 가 못 잡으므로 같은 형식으로 직접 씁니다. */
    private static void writeError(HttpServletResponse res, ErrorCode code) throws IOException {
        res.setStatus(code.status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"success\":false,\"error\":{\"code\":\"" + code.name()
                + "\",\"message\":\"" + code.defaultMessage() + "\",\"details\":null}}");
    }
}
