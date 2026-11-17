package kr.astra.llm.service;

import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;

/**
 * AI 가 부가 정보(설명·질문 문구·조건 추출)만 맡는 곳에서 규칙 기반 결과로 대신할 수 있는 실패인지 판단합니다.
 * 응답 형식 오류·공급자 오류(502)와 한도 초과(429)만 해당하며, 권한·검증 오류는 그대로 올립니다.
 */
public final class AiFallback {
    private AiFallback() {}
    public static boolean recoverable(BusinessException e) {
        return e.getErrorCode() == ErrorCode.AI_UPSTREAM_ERROR || e.getErrorCode() == ErrorCode.TOO_MANY_REQUESTS;
    }
}
