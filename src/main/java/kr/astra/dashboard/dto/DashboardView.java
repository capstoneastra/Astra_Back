package kr.astra.dashboard.dto;

/**
 * GET /dashboard/summary?view= 요약 화면 종류.
 * 한 회원이 의뢰자이면서 장비 보유 조직 구성원일 수 있어 두 화면을 골라 봅니다 (조직 유형·역할과 무관).
 */
public enum DashboardView {
    /** 의뢰자 화면: 내가 보낸 의뢰, 처리할 의뢰, 최근 상담 */
    REQUESTER,
    /** 장비 보유 조직 화면: 우리 조직이 받은 의뢰, 검토 대기, 다가오는 예약, 갱신 필요 장비 */
    PROVIDER,
    /** 플랫폼 관리자 화면 */
    ADMIN
}
