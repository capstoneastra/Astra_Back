package kr.astra.auth.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import kr.astra.auth.entity.EmailVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    /** 그 이메일로 가장 최근에 보낸 인증 (확인할 때는 최근 코드만 유효) */
    Optional<EmailVerification> findFirstByEmailOrderByIdDesc(String email);

    Optional<EmailVerification> findByTokenHash(String tokenHash);

    /** 코드 확인 전에는 코드 만료, 확인 후에는 가입용 토큰 만료를 기준으로 정리합니다. */
    @Modifying
    @Query("DELETE FROM EmailVerification v WHERE COALESCE(v.tokenExpiresAt, v.codeExpiresAt) < :now")
    int deleteExpired(@Param("now") OffsetDateTime now);
}
