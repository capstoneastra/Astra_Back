package kr.astra.llm.dto;
import java.util.*;
import kr.astra.request.entity.RequestStatus;
/** Full api-spec §5 shape. No successful response until BE-B provides a persisted receipt. */
public record AiDraftResponse(Long requestId, String requestNo, RequestStatus status, Revision revision, List<String> aiNotes) {
    public record Revision(int revisionNo, boolean isAiGenerated, Map<String,Object> content) {}
}
