package kr.astra.user.repository;

import java.util.List;
import java.util.Optional;
import kr.astra.user.entity.User;
import kr.astra.user.entity.UserRole;
import kr.astra.user.entity.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

    List<User> findAllByOrganizationIdAndRoleAndStatus(Long organizationId, UserRole role, UserStatus status);

    boolean existsByOrganizationIdAndStatusIn(Long organizationId, List<UserStatus> statuses);

    /** 관리자 회원 목록: role·status 는 null 이면 조건 없음 */
    @Query("""
            SELECT u FROM User u
            WHERE (:role IS NULL OR u.role = :role)
              AND (:status IS NULL OR u.status = :status)
            """)
    Page<User> search(@Param("role") UserRole role, @Param("status") UserStatus status, Pageable pageable);
}
