package kr.astra.request.service;

import kr.astra.request.dto.CreateDraftCommand;
import kr.astra.request.dto.DraftReceipt;

/**
 * [BE-B 제공 → BE-C 사용] AI 의뢰서 초안·자동 추진에서 의뢰를 만들 때 사용. 구현: BE-B (team-guide.md §4 내부 인터페이스)
 * <p>
 * 구현은 {@code @Transactional}(기본 REQUIRED) 이라 호출한 쪽 트랜잭션에 참여합니다.
 * 호출한 쪽이 롤백하면 의뢰·제출본도 함께 롤백되므로 고아 DRAFT 가 남지 않습니다.
 */
public interface TestRequestService {

    /**
     * DRAFT 상태 의뢰와 1번 제출본(revision)을 만듭니다. 자동 제출하지 않습니다 (사용자 승인 필수).
     * <ul>
     *   <li>requesterId 는 활성 기업 회원(COMPANY_USER)이어야 함 (아니면 403 FORBIDDEN)</li>
     *   <li>equipmentId 는 활성 장비여야 함 (아니면 404 EQUIPMENT_NOT_FOUND). 접수 기관은 장비 소속 기관</li>
     *   <li>content.schedule.desiredStartDate/desiredEndDate 가 있으면 의뢰의 희망 일정으로 저장</li>
     * </ul>
     *
     * @return 생성된 test_requests.id
     */
    Long createDraft(CreateDraftCommand command);

    /**
     * createDraft 로 만든 의뢰의 저장 결과 (requestNo, 상태, 1번 제출본). 권한 검사는 하지 않으므로
     * 호출한 쪽에서 이미 소유권을 확인한 의뢰에만 쓰세요.
     *
     * @throws kr.astra.global.exception.BusinessException REQUEST_NOT_FOUND
     */
    DraftReceipt getDraftReceipt(Long requestId);
}
