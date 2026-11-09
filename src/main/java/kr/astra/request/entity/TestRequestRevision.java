package kr.astra.request.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * test_request_revisions: 의뢰서 본문 스냅샷. 제출하면 is_submitted=true 로 고정되고 더 이상 수정하지 않습니다.
 * content 는 api-spec §5 "의뢰서 본문 content 스키마" JSON 문자열 (변환은 RequestContent 에서).
 */
@Getter
@Entity
@Table(name = "test_request_revisions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestRequestRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "revision_no", nullable = false)
    private int revisionNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String content;

    @Column(name = "is_ai_generated", nullable = false)
    private boolean aiGenerated;

    /** false 면 아직 편집 가능한 작업본 */
    @Column(name = "is_submitted", nullable = false)
    private boolean submitted;

    @Column(name = "change_summary", length = 500)
    private String changeSummary;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    public TestRequestRevision(Long requestId, int revisionNo, String contentJson, boolean aiGenerated,
                               String changeSummary, Long authorId) {
        this.requestId = requestId;
        this.revisionNo = revisionNo;
        this.content = contentJson;
        this.aiGenerated = aiGenerated;
        this.changeSummary = changeSummary;
        this.authorId = authorId;
    }

    /** 작업본 내용 수정. 사람이 고치면 더 이상 "AI 가 쓴 그대로"가 아니지만 출처 표시는 유지합니다. */
    public void updateContent(String contentJson, String changeSummary, Long authorId) {
        if (submitted) {
            throw new IllegalStateException("제출된 revision 은 수정할 수 없습니다: " + revisionNo);
        }
        this.content = contentJson;
        if (changeSummary != null) {
            this.changeSummary = changeSummary;
        }
        this.authorId = authorId;
    }

    public void submit(OffsetDateTime now) {
        this.submitted = true;
        this.submittedAt = now;
    }
}
