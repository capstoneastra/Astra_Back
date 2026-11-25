package kr.astra.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * password_resets: 비밀번호 재설정 (V19).
 * 코드 발송 → 코드 확인(verified_at, 재설정 토큰 발급) → 새 비밀번호 저장에 토큰 사용(used_at).
 * 가입하지 않은 이메일이면 userId 가 null (메일은 보내지 않고 응답만 같게).
 */
@Getter
@Entity
@Table(name = "password_resets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordReset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String email;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "code_hash", nullable = false, length = 200)
    private String codeHash;

    @Column(name = "code_expires_at", nullable = false)
    private OffsetDateTime codeExpiresAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "token_hash", unique = true, length = 200)
    private String tokenHash;

    @Column(name = "token_expires_at")
    private OffsetDateTime tokenExpiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public PasswordReset(String email, Long userId, String codeHash, OffsetDateTime codeExpiresAt) {
        this.email = email;
        this.userId = userId;
        this.codeHash = codeHash;
        this.codeExpiresAt = codeExpiresAt;
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public boolean isCodeExpired(OffsetDateTime now) {
        return !codeExpiresAt.isAfter(now);
    }

    /** 틀린 횟수 +1 하고 증가된 값을 돌려줌 */
    public int recordFailedAttempt() {
        return ++attemptCount;
    }

    public void markVerified(OffsetDateTime now, String tokenHash, OffsetDateTime tokenExpiresAt) {
        this.verifiedAt = now;
        this.tokenHash = tokenHash;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    /** 새 비밀번호 저장에 쓸 수 있는 토큰인지: 가입된 회원, 아직 안 썼고, 만료 전 */
    public boolean isTokenUsable(OffsetDateTime now) {
        return userId != null && usedAt == null && tokenExpiresAt != null && tokenExpiresAt.isAfter(now);
    }

    public void markUsed(OffsetDateTime now) {
        this.usedAt = now;
    }
}
