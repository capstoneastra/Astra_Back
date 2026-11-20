package kr.astra.review.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * supplement_requests: 기관의 보완 요청. 의뢰당 OPEN 은 1개만 (uq_supp_one_open_per_request).
 * items 는 [{"fieldKey":"fixture_mass","label":"고정구 질량","required":true}] JSON 문자열.
 */
@Getter
@Entity
@Table(name = "supplement_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupplementRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    /** 어떤 제출본을 보고 요청했는지 */
    @Column(name = "based_revision_no", nullable = false)
    private int basedRevisionNo;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String items;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupplementKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupplementStatus status;

    /** 보완 제출로 생긴 revision 번호 */
    @Column(name = "response_revision_no")
    private Integer responseRevisionNo;

    @Column(name = "response_message", columnDefinition = "text")
    private String responseMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    public SupplementRequest(Long requestId, int basedRevisionNo, Long requestedBy, String message, String itemsJson,
                             LocalDate dueDate, SupplementKind kind) {
        this.kind = kind;
        this.requestId = requestId;
        this.basedRevisionNo = basedRevisionNo;
        this.requestedBy = requestedBy;
        this.message = message;
        this.items = itemsJson;
        this.dueDate = dueDate;
        this.status = SupplementStatus.OPEN;
    }

    public void respond(int revisionNo, String message, OffsetDateTime now) {
        this.status = SupplementStatus.RESPONDED;
        this.responseRevisionNo = revisionNo;
        this.responseMessage = message;
        this.respondedAt = now;
    }

    /** 의뢰가 반려·취소되어 더 이상 답할 필요가 없을 때 */
    public void close() {
        this.status = SupplementStatus.CLOSED;
    }
}
