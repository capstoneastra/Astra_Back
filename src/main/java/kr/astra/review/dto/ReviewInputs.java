package kr.astra.review.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 기관 검토·보완 요청 본문 (api-spec §6). version 은 선택 (보내면 불일치 시 409 VERSION_CONFLICT) */
public final class ReviewInputs {

    private ReviewInputs() {
    }

    /**
     * POST /test-requests/{id}/review/start
     *
     * @param assigneeId 담당자 (장비 보유 조직의 활성 구성원). 비우면 요청한 구성원 본인
     */
    public record StartReview(Integer version, Long assigneeId) {
    }

    /** POST /test-requests/{id}/supplements */
    public record CreateSupplement(
            Integer version,
            @NotBlank @Size(max = 2000) String message,
            @Size(max = 50) List<@NotNull @Valid Item> items,
            LocalDate dueDate) {
    }

    /**
     * 보완 항목. fieldKey = 조건 키(fixture_mass) 또는 의뢰서 content 경로(sample.readyDate).
     * required=true 인 항목은 보완 제출 때 값이 채워져 있어야 합니다.
     */
    public record Item(@NotBlank @Size(max = 100) String fieldKey, @Size(max = 100) String label, boolean required) {
    }

    /**
     * POST /supplements/{id}/respond
     *
     * @param content 수정된 전체 의뢰서 content. 비우면 PUT /draft 로 저장해 둔 작업본을 제출
     */
    public record Respond(
            Integer version,
            @Size(max = 2000) String message,
            Map<String, Object> content,
            @Size(max = 500) String changeSummary) {
    }
}
