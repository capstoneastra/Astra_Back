package kr.astra.user.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import kr.astra.user.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** 그 사용자의 아직 쓸 수 있는 refresh 토큰을 모두 폐기 (계정 정지 시) */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") OffsetDateTime now);
}
