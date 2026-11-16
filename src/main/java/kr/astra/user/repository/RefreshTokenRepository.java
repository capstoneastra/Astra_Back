package kr.astra.user.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import kr.astra.user.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** 재발급 요청끼리 같은 토큰을 동시에 소비하지 못하도록 행을 잠급니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /** 그 사용자의 아직 쓸 수 있는 refresh 토큰을 모두 폐기 (계정 정지 시) */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") OffsetDateTime now);

    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :now")
    int deleteExpired(@Param("now") OffsetDateTime now);
}
