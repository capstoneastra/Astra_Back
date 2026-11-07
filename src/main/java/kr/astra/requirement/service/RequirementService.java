package kr.astra.requirement.service;

import kr.astra.requirement.dto.RequirementSnapshot;

/**
 * [BE-C 제공 → BE-B 사용] 시험조건 조회. 구현: BE-C (team-guide.md §4 내부 인터페이스)
 */
public interface RequirementService {

    /**
     * 시험조건과 조건 항목 전체. 의뢰서 content.conditions 에 반영할 때 씁니다.
     */
    RequirementSnapshot getSnapshot(Long requirementId);
}
