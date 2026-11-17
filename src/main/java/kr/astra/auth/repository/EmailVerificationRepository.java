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

    /**
     * 이메일별 트랜잭션 잠금 (커밋·롤백 시 자동 해제). 아직 행이 없는 첫 요청끼리도 직렬화해
     * 동시에 두 번 보내 둘 다 저장·발송되는 것을 막습니다.
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext(:email))", nativeQuery = true)
    Integer lockEmail(@Param("email") String email);

    Optional<EmailVerification> findByTokenHash(String tokenHash);

    /** 코드 확인 전에는 코드 만료, 확인 후에는 가입용 토큰 만료를 기준으로 정리합니다. */
    @Modifying
    @Query("DELETE FROM EmailVerification v WHERE COALESCE(v.tokenExpiresAt, v.codeExpiresAt) < :now")
    int deleteExpired(@Param("now") OffsetDateTime now);
}
