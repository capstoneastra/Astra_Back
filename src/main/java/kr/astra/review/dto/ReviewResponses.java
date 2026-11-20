package kr.astra.review.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import kr.astra.request.dto.RequestResponses.ActionResult;
import kr.astra.request.dto.RequestResponses.PersonRef;
import kr.astra.review.entity.SupplementStatus;

/** 기관 검토·보완 응답 (api-spec §6) */
public final class ReviewResponses {

    private ReviewResponses() {
    }

    public record SupplementView(
            Long id,
            Long requestId,
            SupplementStatus status,
            int basedRevisionNo,
            PersonRef requestedBy,
            kr.astra.review.entity.SupplementKind kind,
            String message,
            List<ReviewInputs.Item> items,
            LocalDate dueDate,
            Integer responseRevisionNo,
            String responseMessage,
            OffsetDateTime createdAt,
            OffsetDateTime respondedAt) {
    }

    /** POST /test-requests/{id}/supplements 결과: 만든 보완 요청 + 바뀐 의뢰 상태 */
    public record SupplementCreated(SupplementView supplement, ActionResult request) {
    }
}
