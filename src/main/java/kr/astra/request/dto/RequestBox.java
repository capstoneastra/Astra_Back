package kr.astra.request.dto;

/**
 * GET /test-requests?box= 의뢰 목록 종류. 한 회원이 의뢰자이면서 장비 보유 조직 구성원일 수 있어 둘을 나눠 봅니다.
 */
public enum RequestBox {
    /** 내가 보낸 의뢰 (작성 중 포함) */
    SENT,
    /** 우리 조직 장비로 들어온 의뢰 (제출된 것만) */
    RECEIVED
}
