package kr.astra.global.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import kr.astra.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authorization: Bearer &lt;accessToken&gt; 를 검증해 {@link CurrentUser} 를 SecurityContext 에 넣습니다.
 * 컨트롤러에서는 {@code @AuthenticationPrincipal CurrentUser user} 로 받습니다.
 * <p>
 * 토큰이 없거나 잘못되었으면 인증 없이 다음 필터로 넘깁니다. 보호된 경로면 SecurityConfig 의 진입점이
 * 401 을 응답하는데, 토큰이 만료된 경우에만 AUTH_REQUIRED 대신 TOKEN_EXPIRED 를 씁니다 (프론트가 refresh 시도).
 * <p>
 * 토큰이 정상이어도 계정이 정지·거절·승인 대기면 인증하지 않습니다 → 보호된 경로는 403 ACCOUNT_NOT_ACTIVE,
 * 계정이 없으면 401 AUTH_REQUIRED. 관리자가 정지하면 남은 토큰 유효 시간과 상관없이 다음 요청부터 막힙니다.
 * 가입·로그인 같은 공개 경로는 그대로 쓸 수 있습니다.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 진입점이 401 코드를 고를 때 보는 요청 속성 */
    public static final String AUTH_ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".error";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final AccountStatusChecker accountStatusChecker;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                CurrentUser user = jwtProvider.parseAccessToken(token);
                switch (accountStatusChecker.check(user.userId())) {
                    case ACTIVE -> {
                        var authentication = new UsernamePasswordAuthenticationToken(user, null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                    case NOT_ACTIVE -> request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.ACCOUNT_NOT_ACTIVE);
                    case NOT_FOUND -> request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.AUTH_REQUIRED);
                }
            } catch (ExpiredJwtException e) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.TOKEN_EXPIRED);
            } catch (JwtException | IllegalArgumentException e) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.AUTH_REQUIRED);
            } catch (DataAccessException e) {
                // 계정 상태 조회 실패(DB 지연·커넥션 부족): 통과시키지 않고 같은 응답 형식의 500
                log.error("계정 상태 확인 실패", e);
                FilterErrorWriter.write(response, ErrorCode.INTERNAL_ERROR);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
