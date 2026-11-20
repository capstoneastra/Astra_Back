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

    /** PUT /test-requests/{id}/estimated-fee — 장비 보유 조직이 적는 예상 이용료(원) */
    public record EstimatedFee(
            Integer version,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long amount,
            @Size(max = 500) String note) {
    }

    /** PUT /test-requests/{id}/result — 시험 결과·성적서. reportUrl 은 http(s) 주소만 */
    public record Result(
            @jakarta.validation.constraints.NotBlank @Size(max = 4000) String summary,
            @Size(max = 100) String reportNo,
            @Size(max = 1000) @jakarta.validation.constraints.Pattern(regexp = "^https?://\\S+$",
                    message = "성적서 링크는 http:// 또는 https:// 주소여야 합니다.") String reportUrl) {
    }
}
