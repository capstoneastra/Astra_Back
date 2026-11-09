package kr.astra.request.dto;

import java.util.Map;
import kr.astra.request.entity.RequestStatus;

/**
 * [BE-B 제공 → BE-C 사용] createDraft 로 만든 의뢰의 저장 결과 (TestRequestService.getDraftReceipt).
 * POST /test-requests/ai-draft 응답(api-spec §5)의 requestId·requestNo·status·revision 을 이 값으로 채우면 됩니다.
 *
 * @param revisionNo  1번 제출본(작업본) 번호
 * @param aiGenerated test_request_revisions.is_ai_generated
 * @param content     저장된 의뢰서 본문 그대로
 */
public record DraftReceipt(Long requestId, String requestNo, RequestStatus status, int version, int revisionNo,
                           boolean aiGenerated, Map<String, Object> content) {
}
