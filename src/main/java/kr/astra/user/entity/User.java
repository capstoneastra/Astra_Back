package kr.astra.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import kr.astra.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * users: 기업 사용자 · 기관 담당자 · 플랫폼 관리자.
 * 조직은 id 로만 들고 있습니다 (organization 도메인은 OrganizationService 로 조회).
 */
@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(nullable = false, unique = true, length = 200)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 30)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    /**
     * 회원가입. 기업·기관 담당자 모두 관리자 승인 전까지 PENDING (api-spec §1 가입 흐름).
     * 관리자 계정은 가입으로 만들 수 없으므로 호출 전에 막아야 합니다.
     */
    public static User signUp(Long organizationId, String email, String passwordHash, String name, String phone,
                              UserRole role) {
        if (role == UserRole.ADMIN) {
            throw new IllegalArgumentException("관리자 계정은 회원가입으로 만들 수 없습니다.");
        }
        User user = new User();
        user.organizationId = organizationId;
        user.email = email;
        user.passwordHash = passwordHash;
        user.name = name;
        user.phone = phone;
        user.role = role;
        user.status = UserStatus.PENDING;
        return user;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isPending() {
        return status == UserStatus.PENDING;
    }

    /** 관리자 가입 승인: PENDING → ACTIVE */
    public void approve(Long adminId, OffsetDateTime now) {
        this.status = UserStatus.ACTIVE;
        this.approvedBy = adminId;
        this.approvedAt = now;
        this.rejectReason = null;
    }

    /** 관리자 가입 거절: PENDING → REJECTED */
    public void reject(String reason) {
        this.status = UserStatus.REJECTED;
        this.rejectReason = reason;
    }

    /** 관리자 정지(SUSPENDED) 또는 해제(ACTIVE) */
    public void changeStatus(UserStatus status) {
        this.status = status;
    }

    public void recordLogin(OffsetDateTime now) {
        this.lastLoginAt = now;
    }

    public void updateProfile(String name, String phone) {
        if (name != null) {
            this.name = name;
        }
        if (phone != null) {
            this.phone = phone;
        }
    }
}
