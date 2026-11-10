package kr.astra.request.service;

import kr.astra.request.entity.RequestStatus;

/**
 * 의뢰가 반려·취소로 끝났을 때 같은 트랜잭션 안에서 발행됩니다.
 * 보완 요청(review)·예약(reservation)이 이 이벤트를 받아 열린 항목을 닫습니다 (서로의 repository 를 직접 쓰지 않기 위해).
 */
public record RequestClosedEvent(Long requestId, RequestStatus status) {
}
