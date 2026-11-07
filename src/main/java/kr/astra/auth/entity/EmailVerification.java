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
 * email_verifications: 회원가입 이메일 인증 (V4).
 * 코드 발송 → 코드 확인(verified_at, 가입용 토큰 발급) → 가입에 토큰 사용(used_at).
 * 코드·토큰 원문은 저장하지 않고 SHA-256 해시만 저장합니다.
 */
@Getter
@Entity
@Table(name = "email_verifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String email;

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

    public EmailVerification(String email, String codeHash, OffsetDateTime codeExpiresAt) {
        this.email = email;
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

    /** 가입에 쓸 수 있는 토큰인지: 이메일 일치, 아직 안 썼고, 만료 전 */
    public boolean isTokenUsable(String email, OffsetDateTime now) {
        return this.email.equals(email) && usedAt == null && tokenExpiresAt != null && tokenExpiresAt.isAfter(now);
    }

    public void markUsed(OffsetDateTime now) {
        this.usedAt = now;
    }
}
