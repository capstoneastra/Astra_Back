package kr.astra.request.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import kr.astra.request.entity.RequestAction;
import kr.astra.request.entity.RequestStatus;
import kr.astra.reservation.dto.ReservationResponses.ReservationView;
import kr.astra.review.dto.ReviewResponses.SupplementView;

/** 시험 의뢰 응답 (api-spec §5) */
public final class RequestResponses {

    private RequestResponses() {
    }

    public record EquipmentRef(Long id, String externalId, String name, boolean isMock) {
    }

    public record OrgRef(Long id, String name) {
    }

    public record PersonRef(Long id, String name) {
    }

    /** 목록 한 줄 (GET /test-requests) */
    public record Summary(
            Long id,
            String requestNo,
            String title,
            RequestStatus status,
            int version,
            int currentRevisionNo,
            EquipmentRef equipment,
            OrgRef institution,
            PersonRef requester,
            String companyName,
            LocalDate desiredStartDate,
            LocalDate desiredEndDate,
            OffsetDateTime submittedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    /**
     * 상세 (GET /test-requests/{id})
     *
     * @param revision         현재 제출본 (아직 한 번도 제출 안 했으면 1번 작업본)
     * @param workingRevision  보완 요청 중 저장해 둔 다음 작업본 (없으면 null, 의뢰자·관리자에게만)
     * @param openSupplement   답변 대기 중인 보완 요청 (없으면 null)
     * @param reservations     일정 제안·확정 목록 (최신순)
     * @param availableActions 로그인 사용자가 지금 누를 수 있는 버튼 (상태 전이표 기준)
     */
    public record Detail(
            Long id,
            String requestNo,
            String title,
            RequestStatus status,
            int version,
            int currentRevisionNo,
            EquipmentRef equipment,
            OrgRef institution,
            PersonRef requester,
            String companyName,
            PersonRef assignee,
            Long requirementId,
            Long matchResultId,
            LocalDate desiredStartDate,
            LocalDate desiredEndDate,
            OffsetDateTime submittedAt,
            OffsetDateTime closedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Revision revision,
            Revision workingRevision,
            SupplementView openSupplement,
            List<ReservationView> reservations,
            List<RequestAction> availableActions,
            EstimatedFee estimatedFee,
            ResultView result) {
    }

    /** 장비 보유 조직이 등록한 시험 결과·성적서 (아직 없으면 detail.result = null) */
    public record ResultView(String summary, String reportNo, String reportUrl, PersonRef registeredBy,
                             OffsetDateTime registeredAt, OffsetDateTime updatedAt) {
    }

    /** 장비 보유 조직이 적은 예상 이용료 (아직 없으면 detail.estimatedFee = null) */
    public record EstimatedFee(long amount, String note, OffsetDateTime updatedAt) {
    }

    /** 제출본 (목록에서는 content=null) */
    public record Revision(
            int revisionNo,
            boolean isAiGenerated,
            boolean isSubmitted,
            String changeSummary,
            PersonRef author,
            OffsetDateTime createdAt,
            OffsetDateTime submittedAt,
            Map<String, Object> content) {
    }

    /** GET /test-requests/{id}/revisions/{no}?diffWith=1 */
    public record RevisionDetail(Revision revision, Integer diffWith, List<FieldChange> changes) {
    }

    /**
     * 바뀐 값 하나. path 예: "sample.readyDate", "conditions.fixture_mass.value" (조건은 key 로 묶음)
     */
    public record FieldChange(String path, Object before, Object after) {
    }

    /** GET /test-requests/{id}/history 한 줄 */
    public record History(RequestStatus fromStatus, RequestStatus toStatus, PersonRef changedBy, String reason,
                          OffsetDateTime createdAt) {
    }

    /** 상태 변경 결과 (submit, accept, respond …). 다음 요청에 version 을 그대로 보내면 됩니다. */
    public record ActionResult(Long requestId, String requestNo, RequestStatus status, int version, int revisionNo) {
    }

    /** PUT /test-requests/{id}/draft 결과 */
    public record DraftSaved(Long requestId, RequestStatus status, int version, int revisionNo,
                             Map<String, Object> content) {
    }
}
