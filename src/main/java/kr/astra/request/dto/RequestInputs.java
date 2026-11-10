package kr.astra.request.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 시험 의뢰 요청 본문 (api-spec §5). 상태를 바꾸는 요청의 version 은 선택이며,
 * 보내면 현재 version 과 다를 때 409 VERSION_CONFLICT (화면을 연 뒤 다른 사람이 바꾼 경우).
 */
public final class RequestInputs {

    private RequestInputs() {
    }

    /** POST /test-requests — 기업이 직접 의뢰 작성 시작 (DRAFT + 1번 작업본) */
    public record Create(
            @NotNull Long equipmentId,
            Long requirementId,
            Long matchResultId,
            @Size(max = 200) String title) {
    }

    /** PUT /test-requests/{id}/draft — 작업본 내용 저장 (DRAFT, SUPPLEMENT_REQUESTED) */
    public record SaveDraft(
            Integer version,
            @Size(max = 200) String title,
            @NotNull Map<String, Object> content,
            @Size(max = 500) String changeSummary) {
    }

    /** submit / accept / complete 처럼 본문이 version 뿐인 요청 */
    public record VersionOnly(Integer version) {
    }

    /** POST /test-requests/{id}/cancel */
    public record Cancel(Integer version, @Size(max = 500) String reason) {
    }

    /** POST /test-requests/{id}/reject — 사유 필수 */
    public record Reject(Integer version, @NotBlank @Size(max = 500) String reason) {
    }
}
