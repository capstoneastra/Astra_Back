package kr.astra.user.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import kr.astra.user.entity.User;
import kr.astra.user.entity.UserRole;
import kr.astra.user.entity.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    List<User> findAllByRoleAndStatus(UserRole role, UserStatus status);

    long countByStatus(UserStatus status);

    /** 계정 상태만 (JWT 필터가 요청마다 확인할 때) */
    @Query("SELECT u.status FROM User u WHERE u.id = :id")
    Optional<UserStatus> findStatusById(@Param("id") Long id);

    /** 마지막 로그인 시각만 갱신 (버전을 올리지 않으므로 관리자 상태 변경과 충돌하지 않음) */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE User u SET u.lastLoginAt = :now WHERE u.id = :id")
    int updateLastLoginAt(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /** 계정 찾기: 이름이 같고 연락처의 숫자만 비교해 같은 회원의 이메일 (하이픈·공백 무시) */
    @Query(value = """
            SELECT email FROM users
            WHERE name = :name AND regexp_replace(coalesce(phone, ''), '[^0-9]', '', 'g') = :phoneDigits
            ORDER BY email
            """, nativeQuery = true)
    List<String> findEmailsByNameAndPhoneDigits(@Param("name") String name, @Param("phoneDigits") String phoneDigits);

    List<User> findAllByOrganizationIdAndStatus(Long organizationId, UserStatus status);

    boolean existsByOrganizationIdAndStatusIn(Long organizationId, List<UserStatus> statuses);

    /** 관리자 회원 목록: role·status 는 null 이면 조건 없음 */
    @Query("""
            SELECT u FROM User u
            WHERE (:role IS NULL OR u.role = :role)
              AND (:status IS NULL OR u.status = :status)
            """)
    Page<User> search(@Param("role") UserRole role, @Param("status") UserStatus status, Pageable pageable);
}
