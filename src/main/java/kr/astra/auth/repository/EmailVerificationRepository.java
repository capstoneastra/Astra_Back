package kr.astra.auth.repository;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import kr.astra.auth.entity.EmailVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    /** 최근 인증 행을 잠가 동시 코드 확인의 실패 횟수와 인증 상태 갱신을 직렬화합니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerification> findFirstByEmailOrderByIdDesc(String email);

    Optional<EmailVerification> findByTokenHash(String tokenHash);

    /** 코드 확인 전에는 코드 만료, 확인 후에는 가입용 토큰 만료를 기준으로 정리합니다. */
    @Modifying
    @Query("DELETE FROM EmailVerification v WHERE COALESCE(v.tokenExpiresAt, v.codeExpiresAt) < :now")
    int deleteExpired(@Param("now") OffsetDateTime now);
}
