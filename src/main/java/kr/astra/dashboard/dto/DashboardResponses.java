package kr.astra.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import kr.astra.chat.dto.ChatSessionSummary;
import kr.astra.equipment.entity.EquipmentType;
import kr.astra.request.dto.RequestActionItem;
import kr.astra.request.dto.UpcomingReservationItem;
import kr.astra.request.entity.RequestStatus;
import kr.astra.user.entity.UserRole;

/**
 * 대시보드 응답 (api-spec §10). 한 파일에 모아 둡니다.
 * <p>
 * 의뢰·예약(BE-B)과 상담(BE-C) 칸은 그쪽 구현이 생기기 전까지 0·빈 목록이고, dataAvailable 이 false 입니다.
 * 프론트는 false 인 칸을 "준비 중"으로 보여주면 됩니다.
 */
public final class DashboardResponses {

    private DashboardResponses() {
    }

    // ------------------------------------------------------------ GET /dashboard/equipment-stats

    /**
     * @param byRegion       지역 → 장비 수 (가나다순)
     * @param byEquipmentType 장비 유형 → 장비 수 (장비가 있는 유형만)
     * @param byTestType     시험유형별 장비 수 (15개 전부, 표시 순서)
     * @param institutions   기관별 보유·기관 확인 현황 (장비 수 많은 순)
     */
    public record EquipmentStats(long total, Map<String, Long> byRegion, Map<EquipmentType, Long> byEquipmentType,
                                 List<TestTypeCount> byTestType, List<InstitutionStat> institutions) {
    }

    public record TestTypeCount(String code, String nameKo, long equipmentCount) {
    }

    /**
     * @param verifiedCount    기관이 확인(INSTITUTION_VERIFIED)한 장비 수
     * @param verificationRate 기관 확인 비율 % (소수 첫째 자리까지)
     */
    public record InstitutionStat(Long id, String name, String region, long equipmentCount, long verifiedCount,
                                  double verificationRate) {
    }

    // ------------------------------------------------------------ GET /dashboard/summary

    /**
     * 요약 공통: 모든 응답에 role(로그인한 회원의 역할)과 view(이 응답의 화면 종류)가 들어감.
     * 응답 형식은 role 이 아니라 view 로 구분합니다 (REQUESTER → CompanySummary, PROVIDER → InstitutionSummary).
     */
    public sealed interface Summary permits CompanySummary, InstitutionSummary, AdminSummary {
        UserRole role();

        DashboardView view();
    }

    /** 전체 장비 요약 (모든 역할 공통) */
    public record EquipmentOverview(long total, Map<String, Long> byRegion) {
    }

    /** 다른 담당자 데이터가 연결됐는지 (requests: BE-B 의뢰·예약, chats: BE-C 상담, 해당 없는 역할은 생략) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DataAvailable(boolean requests, Boolean chats) {
    }

    /** 의뢰자 화면(view=REQUESTER): 내가 보낸 의뢰 상태별 수, 처리할 의뢰, 최근 상담, 장비 요약 */
    public record CompanySummary(UserRole role, DashboardView view, Map<RequestStatus, Long> requestCounts,
                                 List<RequestActionItem> actionRequired, List<ChatSessionSummary> recentChats,
                                 EquipmentOverview equipmentStats, DataAvailable dataAvailable) implements Summary {
    }

    /**
     * 장비 보유 조직 화면(view=PROVIDER): 우리 조직이 받은 의뢰 상태별 수, 검토 대기, 다가오는 예약,
     * 갱신 필요 장비 수, 장비 요약. institution 은 로그인한 회원의 소속 조직(기업·기관·대학)
     */
    public record InstitutionSummary(UserRole role, DashboardView view, InstitutionRef institution,
                                     Map<RequestStatus, Long> requestCounts,
                                     List<RequestActionItem> pendingReview,
                                     List<UpcomingReservationItem> upcomingReservations,
                                     NeedsUpdate equipmentNeedsUpdate, EquipmentOverview equipmentStats,
                                     DataAvailable dataAvailable) implements Summary {
    }

    public record InstitutionRef(Long id, String name) {
    }

    /** 갱신 필요 장비 수: total = 둘 중 하나라도 해당 (기관 확인 전 / 마지막 갱신 90일 경과) */
    public record NeedsUpdate(long total, long notConfirmed, long outdated) {
    }

    /** ADMIN: 승인 대기 회원·기관 수, 전체 의뢰 상태별 수, 기관별 장비 확인 비율, 장비 요약 */
    public record AdminSummary(UserRole role, DashboardView view, long pendingUsers, long pendingInstitutions,
                               Map<RequestStatus, Long> requestCounts, List<InstitutionStat> institutionVerification,
                               EquipmentOverview equipmentStats, DataAvailable dataAvailable) implements Summary {
    }
}
