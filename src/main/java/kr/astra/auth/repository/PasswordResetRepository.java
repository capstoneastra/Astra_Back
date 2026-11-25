package kr.astra.auth.repository;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import kr.astra.auth.entity.PasswordReset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {

    /** 최근 행을 잠가 동시 코드 확인의 실패 횟수와 확인 상태 갱신을 직렬화합니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordReset> findFirstByEmailOrderByIdDesc(String email);

    /** 이메일별 트랜잭션 잠금 (회원가입 인증과 키가 겹치지 않게 접두어를 붙임) */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext('pw-reset:' || :email))", nativeQuery = true)
    Integer lockEmail(@Param("email") String email);

    /** 같은 토큰으로 동시에 비밀번호를 바꾸지 못하도록 행을 잠급니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PasswordReset p WHERE p.tokenHash = :tokenHash")
    Optional<PasswordReset> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("DELETE FROM PasswordReset p WHERE COALESCE(p.tokenExpiresAt, p.codeExpiresAt) < :now")
    int deleteExpired(@Param("now") OffsetDateTime now);
}
