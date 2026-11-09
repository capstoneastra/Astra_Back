package kr.astra.global.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.security.AccountStatusChecker;
import kr.astra.global.security.JwtAuthenticationFilter;
import kr.astra.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 보안 설정 (api-spec.md §0-4).
 * <ul>
 *   <li>CORS: CorsConfig (프론트 주소는 CORS_ALLOWED_ORIGINS)</li>
 *   <li>비로그인 허용: /api/v1/auth/**, Swagger, 헬스체크</li>
 *   <li>로그아웃(POST /api/v1/auth/logout)은 /auth 아래지만 로그인 필요</li>
 *   <li>그 외 전부 로그인 필요 (astra.security.require-login=true, 기본값)</li>
 * </ul>
 * JWT 인증 필터가 Authorization: Bearer 토큰을 검증해 {@code @AuthenticationPrincipal CurrentUser} 로 넣어 줍니다.
 * 역할 계층: ADMIN 은 다른 모든 역할을 포함하므로 {@code @PreAuthorize("hasRole('INSTITUTION_MANAGER')")} 도 통과합니다.
 * <p>
 * 로컬에서 잠깐 로그인 없이 호출해 보려면 astra.security.require-login=false 로 실행할 수 있습니다 (커밋 금지).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            WebConfig.API_PREFIX + "/auth/**",
            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
            "/actuator/health"
    };

    private static final String LOGOUT_PATH = WebConfig.API_PREFIX + "/auth/logout";

    @Value("${astra.security.require-login:true}")
    private boolean requireLogin;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtProvider jwtProvider,
                                                   AccountStatusChecker accountStatusChecker) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults()) // CorsConfig 의 corsConfigurationSource 사용 (401·403 에도 CORS 헤더)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider, accountStatusChecker), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> {
                    // SSE(SseEmitter)·비동기 응답이 끝날 때와 오류 페이지로 넘길 때의 내부 재요청(ASYNC·ERROR dispatch)은
                    // JWT 필터가 다시 돌지 않아 비로그인으로 보임 → 막으면 스트림이 중간에 끊김 (응답이 이미 나가는 중이라
                    // "response is already committed"). 원래 요청에서 이미 인증·권한을 통과했으므로 여기서는 허용
                    auth.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll();
                    auth.requestMatchers(HttpMethod.POST, LOGOUT_PATH).authenticated();
                    auth.requestMatchers(PUBLIC_PATHS).permitAll();
                    if (requireLogin) {
                        auth.anyRequest().authenticated();
                    } else {
                        auth.anyRequest().permitAll();
                    }
                })
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, authErrorOf(req)))
                        .accessDeniedHandler((req, res, ex) -> writeError(res, ErrorCode.FORBIDDEN)));
        return http.build();
    }

    /** ADMIN 은 마스터 권한: 다른 역할이 필요한 곳도 모두 통과 */
    @Bean
    public static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
                ROLE_ADMIN > ROLE_INSTITUTION_MANAGER
                ROLE_ADMIN > ROLE_COMPANY_USER
                """);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
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

    /**
     * 토큰이 만료됐으면 TOKEN_EXPIRED(401), 계정이 정지·거절·승인 대기면 ACCOUNT_NOT_ACTIVE(403),
     * 그 외(없음·위조·형식 오류·계정 없음)는 AUTH_REQUIRED(401)
     */
    private static ErrorCode authErrorOf(HttpServletRequest req) {
        return req.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE) instanceof ErrorCode code
                ? code : ErrorCode.AUTH_REQUIRED;
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
