package kr.astra.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 계정_상태_조회가_DB_오류로_실패하면_ApiResponse_형식의_500_INTERNAL_ERROR() throws Exception {
        JwtProvider jwtProvider = mock(JwtProvider.class);
        AccountStatusChecker checker = mock(AccountStatusChecker.class);
        when(jwtProvider.parseAccessToken("tok")).thenReturn(new CurrentUser(1L, UserRole.COMPANY_USER, null));
        when(checker.check(1L)).thenThrow(new DataAccessResourceFailureException("pool exhausted"));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/me");
        req.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse res = new MockHttpServletResponse();

        new JwtAuthenticationFilter(jwtProvider, checker).doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(500);
        assertThat(res.getContentType()).startsWith("application/json");
        assertThat(res.getContentAsString())
                .contains("\"success\":false")
                .contains("\"code\":\"INTERNAL_ERROR\"");
        verify(chain, never()).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
