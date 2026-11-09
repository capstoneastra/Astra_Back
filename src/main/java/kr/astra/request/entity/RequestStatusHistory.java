package kr.astra.request.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** request_status_history: 상태 변경 감사 로그. 관리자가 대신 처리해도 changed_by 에 관리자 id 가 남습니다. */
@Getter
@Entity
@Table(name = "request_status_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    /** 의뢰 생성 기록은 null */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private RequestStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private RequestStatus toStatus;

    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    @Column(length = 500)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public RequestStatusHistory(Long requestId, RequestStatus fromStatus, RequestStatus toStatus, Long changedBy,
                                String reason) {
        this.requestId = requestId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedBy = changedBy;
        this.reason = reason;
    }
}
