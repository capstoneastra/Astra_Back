package kr.astra.auth.repository;

import java.util.Optional;
import kr.astra.auth.entity.EmailVerification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    /** 그 이메일로 가장 최근에 보낸 인증 (확인할 때는 최근 코드만 유효) */
    Optional<EmailVerification> findFirstByEmailOrderByIdDesc(String email);

    Optional<EmailVerification> findByTokenHash(String tokenHash);
}
