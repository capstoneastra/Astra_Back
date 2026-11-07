package kr.astra.request.service;

import kr.astra.request.dto.CreateDraftCommand;

/**
 * [BE-B 제공 → BE-C 사용] AI 의뢰서 초안·자동 추진에서 의뢰를 만들 때 사용. 구현: BE-B (team-guide.md §4 내부 인터페이스)
 */
public interface TestRequestService {

    /**
     * DRAFT 상태 의뢰와 1번 제출본(revision)을 만듭니다. 자동 제출하지 않습니다 (사용자 승인 필수).
     *
     * @return 생성된 test_requests.id
     */
    Long createDraft(CreateDraftCommand command);
}
