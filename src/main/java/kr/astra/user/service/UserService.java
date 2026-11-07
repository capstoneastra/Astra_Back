package kr.astra.user.service;

import java.time.OffsetDateTime;
import java.util.Locale;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.DbConstraints;
import kr.astra.global.exception.ErrorCode;
import kr.astra.organization.service.OrganizationService;
import kr.astra.user.dto.UserAccount;
import kr.astra.user.dto.UserMeResponse;
import kr.astra.user.dto.UserUpdateRequest;
import kr.astra.user.entity.User;
import kr.astra.user.entity.UserRole;
import kr.astra.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자(users) 생성·인증·조회. 다른 도메인은 이 서비스만 사용합니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final OrganizationService organizationService;
    private final PasswordEncoder passwordEncoder;

    /** 이메일은 앞뒤 공백을 지우고 소문자로 저장·비교합니다. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** 이미 가입된 이메일이면 409 EMAIL_ALREADY_EXISTS (조직을 만들기 전에 먼저 확인할 때 사용) */
    public void checkEmailAvailable(String email) {
        if (userRepository.existsByEmail(normalizeEmail(email))) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    /** 회원가입. 이미 있는 이메일이면 409 EMAIL_ALREADY_EXISTS */
    @Transactional
    public UserAccount register(Long organizationId, String email, String rawPassword, String name, String phone,
                                UserRole role) {
        checkEmailAvailable(email);
        String normalized = normalizeEmail(email);
        User user = User.signUp(organizationId, normalized, passwordEncoder.encode(rawPassword), name.trim(),
                phone, role);
        try {
            return UserAccount.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            if (DbConstraints.UNIQUE_VIOLATION.equals(DbConstraints.sqlState(e))) {
                throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
            }
            throw e;
        }
    }

    /**
     * 로그인. 이메일·비밀번호가 틀리면 401 INVALID_CREDENTIALS (어느 쪽이 틀렸는지는 알려주지 않음),
     * 맞더라도 ACTIVE 가 아니면 403 ACCOUNT_NOT_ACTIVE.
     */
    @Transactional
    public UserAccount authenticate(String email, String rawPassword) {
        User user = userRepository.findByEmail(normalizeEmail(email))
                .filter(u -> passwordEncoder.matches(rawPassword, u.getPasswordHash()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        user.recordLogin(OffsetDateTime.now());
        return UserAccount.from(user);
    }

    /** 토큰 재발급 시: 계정이 지워졌으면 401 AUTH_REQUIRED, 정지·거절되었으면 403 ACCOUNT_NOT_ACTIVE */
    public UserAccount getActiveAccount(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        return UserAccount.from(user);
    }

    public UserMeResponse getMe(Long userId) {
        User user = findUser(userId);
        return UserMeResponse.of(user, organizationService.findSummary(user.getOrganizationId()).orElse(null));
    }

    @Transactional
    public UserMeResponse updateMe(Long userId, UserUpdateRequest request) {
        User user = findUser(userId);
        user.updateProfile(request.name() == null ? null : request.name().trim(), request.phone());
        userRepository.flush();
        return UserMeResponse.of(user, organizationService.findSummary(user.getOrganizationId()).orElse(null));
    }

    private User findUser(Long userId) {
        // 토큰은 유효한데 사용자가 없는 경우(삭제 등)는 다시 로그인하게 함
        return userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
    }
}
