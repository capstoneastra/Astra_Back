package kr.astra.request.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import kr.astra.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * test_requests: 시험 의뢰 1건. 상태는 {@link RequestAction} 표대로만 바뀝니다 (RequestWorkflow.transition).
 * 의뢰서 본문은 test_request_revisions 에 제출본마다 따로 저장하고, 여기에는 현재 제출본 번호만 둡니다.
 */
@Getter
@Entity
@Table(name = "test_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_no", nullable = false, unique = true, length = 30)
    private String requestNo;

    @Column(name = "requester_id", nullable = false)
    private Long requesterId;

    @Column(name = "company_org_id")
    private Long companyOrgId;

    @Column(name = "institution_id", nullable = false)
    private Long institutionId;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(name = "match_result_id")
    private Long matchResultId;

    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RequestStatus status;

    /** 마지막으로 제출된(또는 아직 한 번도 제출 안 했으면 1번 작업본) revision 번호 */
    @Column(name = "current_revision_no", nullable = false)
    private int currentRevisionNo;

    @Column(name = "desired_start_date")
    private LocalDate desiredStartDate;

    @Column(name = "desired_end_date")
    private LocalDate desiredEndDate;

    /** 예상 이용료(원). 장비 보유 조직이 입력 */
    @Column(name = "estimated_fee")
    private Long estimatedFee;

    @Column(name = "estimated_fee_note", length = 500)
    private String estimatedFeeNote;

    @Column(name = "estimated_fee_updated_at")
    private OffsetDateTime estimatedFeeUpdatedAt;

    /** 처음 제출한 시각 (기관 접수함은 이 값이 있는 의뢰만 봄) */
    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    /** 반려·취소·완료로 끝난 시각 */
    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    /** 낙관적 락. 클라이언트가 보낸 version 과 다르면 409 VERSION_CONFLICT */
    @Version
    @Column(nullable = false)
    private int version;

    public TestRequest(String requestNo, Long requesterId, Long companyOrgId, Long institutionId, Long equipmentId,
                       Long requirementId, Long matchResultId, String title, LocalDate desiredStartDate,
                       LocalDate desiredEndDate) {
        this.requestNo = requestNo;
        this.requesterId = requesterId;
        this.companyOrgId = companyOrgId;
        this.institutionId = institutionId;
        this.equipmentId = equipmentId;
        this.requirementId = requirementId;
        this.matchResultId = matchResultId;
        this.title = title;
        this.status = RequestStatus.DRAFT;
        this.currentRevisionNo = 1;
        this.desiredStartDate = desiredStartDate;
        this.desiredEndDate = desiredEndDate;
    }

    /** 상태 변경은 RequestWorkflow.transition 에서만 호출합니다 (전이 검증·이력·알림을 함께 처리). */
    public void changeStatus(RequestStatus to, OffsetDateTime now) {
        this.status = to;
        if (to == RequestStatus.SUBMITTED && this.submittedAt == null) {
            this.submittedAt = now;
        }
        if (to == RequestStatus.REJECTED || to == RequestStatus.CANCELED || to == RequestStatus.COMPLETED) {
            this.closedAt = now;
        }
    }

    public void updateDraftInfo(String title, LocalDate desiredStartDate, LocalDate desiredEndDate) {
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
        this.desiredStartDate = desiredStartDate;
        this.desiredEndDate = desiredEndDate;
    }

    public void updateEstimatedFee(long amount, String note, OffsetDateTime now) {
        this.estimatedFee = amount;
        this.estimatedFeeNote = note == null || note.isBlank() ? null : note.strip();
        this.estimatedFeeUpdatedAt = now;
    }

    public void assign(Long assigneeId) {
        if (assigneeId != null) {
            this.assigneeId = assigneeId;
        }
    }

    /** 보완 제출로 새 제출본이 생기면 번호를 올립니다 (PPT 12p "제출본 이력 분리") */
    public void advanceRevision(int revisionNo) {
        this.currentRevisionNo = revisionNo;
    }
}
