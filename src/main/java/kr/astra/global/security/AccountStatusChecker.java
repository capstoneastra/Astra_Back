package kr.astra.global.security;

/**
 * 토큰 주인의 현재 계정 상태 (JwtAuthenticationFilter 가 요청마다 확인).
 * access 토큰은 1시간 유효하지만, 관리자가 정지·거절하면 다음 요청부터 바로 막기 위해 씁니다.
 * 구현: user 도메인 (UserAccountStatusCache, 짧은 캐시 + 상태 변경 시 즉시 삭제).
 */
public interface AccountStatusChecker {

    enum Status {
        /** 이용 가능 */
        ACTIVE,
        /** 승인 대기·거절·정지 → 보호된 API 는 403 ACCOUNT_NOT_ACTIVE */
        NOT_ACTIVE,
        /** 계정이 없음 (삭제 등) → 401 AUTH_REQUIRED */
        NOT_FOUND
    }

    Status check(Long userId);
}
