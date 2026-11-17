package kr.astra.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.organization.service.OrganizationService;
import kr.astra.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserServiceAuthenticateTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = spy(new BCryptPasswordEncoder(4));
    private final UserService userService = new UserService(userRepository, mock(OrganizationService.class),
            passwordEncoder, mock(UserAccountStatusCache.class));

    @Test
    void 없는_이메일도_BCrypt_비교를_해서_응답_시간으로_가입_여부가_드러나지_않는다() {
        when(userRepository.findByEmail("nobody@test.kr")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.authenticate("nobody@test.kr", "some-password"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).matches(eq("some-password"), hash.capture());
        assertThat(hash.getValue()).startsWith("$2a$04$"); // 같은 인코더(같은 비용)로 만든 더미 해시
    }

    @Test
    void 비밀번호를_72바이트_넘게_보내도_401() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        String longPassword = "가".repeat(30); // 30자 = 90바이트
        assertThatThrownBy(() -> userService.authenticate("nobody@test.kr", longPassword))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void 가입_비밀번호가_72바이트를_넘으면_500이_아니라_password_필드_400() {
        assertThatThrownBy(() -> userService.register(1L, "a@test.kr", "가".repeat(30), "홍길동", null,
                kr.astra.user.entity.UserRole.COMPANY_USER))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(be.getDetails().toString()).contains("field=password");
                });
    }
}
