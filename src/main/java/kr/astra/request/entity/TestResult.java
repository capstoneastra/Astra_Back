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

/** 시험 결과·성적서 (V15). 장비 보유 조직이 시험을 마치고 등록, 의뢰당 1건 */
@Getter
@Entity
@Table(name = "test_results")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true)
    private Long requestId;

    @Column(nullable = false, columnDefinition = "text")
    private String summary;

    @Column(name = "report_no", length = 100)
    private String reportNo;

    @Column(name = "report_url", length = 1000)
    private String reportUrl;

    @Column(name = "registered_by", nullable = false)
    private Long registeredBy;

    @Column(name = "registered_at", nullable = false)
    private OffsetDateTime registeredAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public TestResult(Long requestId, Long registeredBy, OffsetDateTime now) {
        this.requestId = requestId;
        this.registeredBy = registeredBy;
        this.registeredAt = now;
        this.updatedAt = now;
    }

    public void update(String summary, String reportNo, String reportUrl, Long by, OffsetDateTime now) {
        this.summary = summary;
        this.reportNo = reportNo;
        this.reportUrl = reportUrl;
        this.registeredBy = by;
        this.updatedAt = now;
    }
}
