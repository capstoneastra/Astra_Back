package kr.astra.request.dto;

import java.util.Map;

/**
 * 의뢰 초안 생성 요청 (TestRequestService.createDraft)
 *
 * @param requesterId   의뢰하는 기업 사용자 users.id
 * @param equipmentId   선택한 장비 equipment.id
 * @param requirementId 시험조건 test_requirements.id (없으면 null)
 * @param matchResultId 추천에서 온 경우 match_results.id (없으면 null)
 * @param title         의뢰 제목
 * @param content       의뢰서 본문 JSON (api-spec.md §5 "의뢰서 본문 content 스키마")
 * @param aiGenerated   AI 가 작성한 초안이면 true
 */
public record CreateDraftCommand(
        Long requesterId,
        Long equipmentId,
        Long requirementId,
        Long matchResultId,
        String title,
        Map<String, Object> content,
        boolean aiGenerated) {
}
