package kr.astra.user.service;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.DbConstraints;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.validation.PasswordPolicy;
import kr.astra.organization.service.OrganizationService;
import kr.astra.user.dto.UserAccount;
import kr.astra.user.dto.UserAdminView;
import kr.astra.user.dto.UserMeResponse;
import kr.astra.user.dto.UserUpdateRequest;
import kr.astra.user.entity.User;
import kr.astra.user.entity.UserRole;
import kr.astra.user.entity.UserStatus;
import kr.astra.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** 관리자 회원 목록에서 정렬할 수 있는 필드 (그 외는 400 VALIDATION_FAILED) */
    private static final Set<String> ADMIN_SORTABLE = Set.of("createdAt", "email", "name", "role", "status",
            "approvedAt", "lastLoginAt");

    private final UserRepository userRepository;
    private final OrganizationService organizationService;
    private final PasswordEncoder passwordEncoder;
    private final UserAccountStatusCache statusCache;

    /** 없는 이메일 로그인 때 비교할 해시 (처음 쓸 때 같은 인코더로 만듦) */
    private volatile String dummyHash;

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
                                UserRole role, String researcherNo) {
        if (!PasswordPolicy.withinBcryptLimit(rawPassword)) {
            throw BusinessException.invalidField("password", PasswordPolicy.TOO_LONG_MESSAGE);
        }
        checkEmailAvailable(email);
        String normalized = normalizeEmail(email);
        User user = User.signUp(organizationId, normalized, passwordEncoder.encode(rawPassword), name.trim(),
                phone, role, researcherNo);
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
     * 맞더라도 ACTIVE 가 아니면 403 ACCOUNT_NOT_ACTIVE (details.status, 거절이면 details.rejectReason 도).
     */
    @Transactional
    public UserAccount authenticate(String email, String rawPassword) {
        Optional<User> found = userRepository.findByEmail(normalizeEmail(email));
        if (found.isEmpty()) {
            // 없는 이메일도 같은 비용의 BCrypt 비교를 해서 응답 시간으로 가입 여부가 드러나지 않게 함
            passwordEncoder.matches(rawPassword, dummyHash());
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        User user = found.get();
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE, notActiveDetails(user));
        }
        // 엔티티 전체가 아니라 이 컬럼만 갱신: 그 사이 관리자가 바꾼 상태를 덮어쓰지 않음
        userRepository.updateLastLoginAt(user.getId(), OffsetDateTime.now());
        return UserAccount.from(user);
    }

    private String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = passwordEncoder.encode(UUID.randomUUID().toString());
            dummyHash = hash;
        }
        return hash;
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

    /** id → 이름 (변경 이력 등에 수정한 사람 이름을 붙일 때). 없는 id 는 빠짐 */
    public Map<Long, String> findNames(Collection<Long> userIds) {
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
    }

    /** 그 조직에 승인 대기·활성 회원이 있으면 true (거절된 신청만 있으면 false) */
    public boolean hasPendingOrActiveMember(Long organizationId) {
        return userRepository.existsByOrganizationIdAndStatusIn(organizationId,
                List.of(UserStatus.PENDING, UserStatus.ACTIVE));
    }

    /** 상태별 회원 수 (관리자 대시보드: 승인 대기 회원 수) */
    public long countByStatus(UserStatus status) {
        return userRepository.countByStatus(status);
    }

    /** 장비 보유 조직(기업·기관·대학)의 활성 구성원 id (장비 갱신·의뢰 접수 알림 받는 사람) */
    public List<Long> findActiveManagerIds(Long institutionId) {
        return userRepository.findAllByOrganizationIdAndStatus(institutionId, UserStatus.ACTIVE).stream()
                .map(User::getId).toList();
    }

    /** 가입 승인 요청 알림을 받을 관리자 id (ACTIVE 관리자 전원) */
    public List<Long> findActiveAdminIds() {
        return userRepository.findAllByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE).stream()
                .map(User::getId)
                .toList();
    }

    /** 관리자 회원 목록 (api-spec §12). role·status 는 null 이면 전체 */
    public Page<UserAdminView> searchForAdmin(UserRole role, UserStatus status, Pageable pageable) {
        pageable.getSort().forEach(order -> {
            if (!ADMIN_SORTABLE.contains(order.getProperty())) {
                throw BusinessException.invalidField("sort",
                        "정렬할 수 없는 필드입니다. 가능: " + String.join(", ", ADMIN_SORTABLE.stream().sorted().toList()));
            }
        });
        return userRepository.search(role, status, pageable).map(UserAdminView::from);
    }

    /** 가입 승인: PENDING 만 가능, 아니면 409 USER_INVALID_STATUS. 없는 회원이면 404 NOT_FOUND */
    @Transactional
    public UserAdminView approve(Long userId, Long adminId) {
        User user = findPendingUser(userId);
        user.approve(adminId, OffsetDateTime.now());
        userRepository.flush();
        statusCache.evictAfterCommit(userId);
        return UserAdminView.from(user);
    }

    /** 가입 거절: PENDING 만 가능, 아니면 409 USER_INVALID_STATUS. 없는 회원이면 404 NOT_FOUND */
    @Transactional
    public UserAdminView reject(Long userId, String reason) {
        User user = findPendingUser(userId);
        user.reject(reason.trim());
        userRepository.flush();
        statusCache.evictAfterCommit(userId);
        return UserAdminView.from(user);
    }

    /**
     * 관리자 정지·해제: ACTIVE → SUSPENDED, SUSPENDED → ACTIVE 만 가능.
     * status 가 둘 중 하나가 아니면 400, 관리자 계정·자기 자신·그 외 상태면 409 USER_INVALID_STATUS.
     */
    @Transactional
    public UserAdminView changeStatus(Long userId, Long adminId, UserStatus status) {
        if (status != UserStatus.ACTIVE && status != UserStatus.SUSPENDED) {
            throw BusinessException.invalidField("status", "정지(SUSPENDED) 또는 해제(ACTIVE)만 할 수 있습니다.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다.", null));
        if (user.getRole() == UserRole.ADMIN || user.getId().equals(adminId)) {
            throw new BusinessException(ErrorCode.USER_INVALID_STATUS, "관리자 계정은 정지·해제할 수 없습니다.",
                    Map.of("status", user.getStatus()));
        }
        UserStatus from = status == UserStatus.SUSPENDED ? UserStatus.ACTIVE : UserStatus.SUSPENDED;
        if (user.getStatus() != from) {
            throw new BusinessException(ErrorCode.USER_INVALID_STATUS,
                    status == UserStatus.SUSPENDED ? "이용 중(ACTIVE)인 회원만 정지할 수 있습니다."
                            : "정지(SUSPENDED)된 회원만 해제할 수 있습니다.",
                    Map.of("status", user.getStatus()));
        }
        user.changeStatus(status);
        userRepository.flush();
        statusCache.evictAfterCommit(userId); // 정지는 다음 요청부터 바로 막힘
        return UserAdminView.from(user);
    }

    private User findPendingUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다.", null));
        if (!user.isPending()) {
            throw new BusinessException(ErrorCode.USER_INVALID_STATUS,
                    "승인 대기(PENDING) 중인 회원만 승인·거절할 수 있습니다.", Map.of("status", user.getStatus()));
        }
        return user;
    }

    private static Map<String, Object> notActiveDetails(User user) {
        return user.getRejectReason() == null
                ? Map.of("status", user.getStatus())
                : Map.of("status", user.getStatus(), "rejectReason", user.getRejectReason());
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
