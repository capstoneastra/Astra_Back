package kr.astra.dashboard.service;

import java.text.Collator;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import kr.astra.chat.dto.ChatSessionSummary;
import kr.astra.chat.service.ChatSessionQuery;
import kr.astra.dashboard.dto.DashboardResponses.AdminSummary;
import kr.astra.dashboard.dto.DashboardResponses.CompanySummary;
import kr.astra.dashboard.dto.DashboardResponses.DataAvailable;
import kr.astra.dashboard.dto.DashboardResponses.EquipmentOverview;
import kr.astra.dashboard.dto.DashboardResponses.EquipmentStats;
import kr.astra.dashboard.dto.DashboardResponses.InstitutionRef;
import kr.astra.dashboard.dto.DashboardResponses.InstitutionStat;
import kr.astra.dashboard.dto.DashboardResponses.InstitutionSummary;
import kr.astra.dashboard.dto.DashboardResponses.NeedsUpdate;
import kr.astra.dashboard.dto.DashboardResponses.Summary;
import kr.astra.dashboard.dto.DashboardResponses.TestTypeCount;
import kr.astra.dashboard.dto.DashboardView;
import kr.astra.equipment.entity.Equipment;
import kr.astra.equipment.repository.EquipmentJdbcRepository.InstitutionVerificationRow;
import kr.astra.equipment.repository.EquipmentJdbcRepository.NeedsUpdateRow;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.global.security.AccessGuard;
import kr.astra.global.security.CurrentUser;
import kr.astra.organization.dto.OrganizationSummaryResponse;
import kr.astra.organization.service.OrganizationService;
import kr.astra.request.dto.RequestActionItem;
import kr.astra.request.dto.UpcomingReservationItem;
import kr.astra.request.entity.RequestStatus;
import kr.astra.request.service.RequestDashboardQuery;
import kr.astra.testtype.service.TestTypeService;
import kr.astra.user.entity.UserRole;
import kr.astra.user.entity.UserStatus;
import kr.astra.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대시보드 (api-spec §10, 기능 14).
 * 장비·회원·기관 집계는 BE-A 데이터로 채우고, 의뢰·예약은 {@link RequestDashboardQuery}(BE-B),
 * 최근 상담은 {@link ChatSessionQuery}(BE-C) 구현이 있을 때만 채웁니다 (없으면 0·빈 목록, dataAvailable=false).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    /** 목록 칸(처리할 의뢰·검토 대기·예약·최근 상담) 최대 개수 */
    static final int LIST_LIMIT = 5;
    /** 이 기간이 지나면 "정보 갱신 필요" (기관 장비 관리와 같은 기준) */
    static final int UPDATE_INTERVAL_DAYS = Equipment.UPDATE_INTERVAL_DAYS;

    private static final Collator KOREAN = Collator.getInstance(Locale.KOREAN);

    private final EquipmentService equipmentService;
    private final TestTypeService testTypeService;
    private final OrganizationService organizationService;
    private final UserService userService;
    private final ObjectProvider<RequestDashboardQuery> requestQuery;
    private final ObjectProvider<ChatSessionQuery> chatQuery;

    /** GET /dashboard/equipment-stats (로그인한 모든 사용자) */
    public EquipmentStats equipmentStats() {
        Map<String, Long> byTestType = equipmentService.countByTestType();
        List<TestTypeCount> testTypes = testTypeService.findAllOrdered().stream()
                .map(t -> new TestTypeCount(t.getCode(), t.getNameKo(), byTestType.getOrDefault(t.getCode(), 0L)))
                .toList();
        List<InstitutionVerificationRow> rows = equipmentService.countVerificationByInstitution();
        return new EquipmentStats(total(rows), byRegion(), equipmentService.countByEquipmentTypeAll(), testTypes,
                institutionStats(rows));
    }

    /**
     * GET /dashboard/summary?view=. 관리자는 항상 관리자 요약.
     * 그 외 회원은 view 로 의뢰자 화면(REQUESTER)과 장비 보유 조직 화면(PROVIDER)을 고르고,
     * 안 보내면 가입 유형에 맞는 기본 화면 (INSTITUTION_MANAGER 는 PROVIDER, COMPANY_USER 는 REQUESTER).
     */
    public Summary summary(CurrentUser user, DashboardView view) {
        CurrentUser u = AccessGuard.requireLogin(user);
        if (u.isAdmin()) {
            return adminSummary();
        }
        DashboardView resolved = view != null && view != DashboardView.ADMIN ? view
                : u.role() == UserRole.INSTITUTION_MANAGER ? DashboardView.PROVIDER : DashboardView.REQUESTER;
        return resolved == DashboardView.PROVIDER ? institutionSummary(u) : companySummary(u);
    }

    private CompanySummary companySummary(CurrentUser u) {
        RequestDashboardQuery requests = requestQuery.getIfAvailable();
        ChatSessionQuery chats = chatQuery.getIfAvailable();
        return new CompanySummary(u.role(), DashboardView.REQUESTER,
                counts(requests == null ? null : () -> requests.countByRequester(u.userId())),
                requests == null ? List.<RequestActionItem>of() : requests.findActionRequired(u.userId(), LIST_LIMIT),
                chats == null ? List.<ChatSessionSummary>of() : chats.findRecent(u.userId(), LIST_LIMIT),
                overview(), new DataAvailable(requests != null, chats != null));
    }

    private InstitutionSummary institutionSummary(CurrentUser u) {
        Long institutionId = u.organizationId();
        RequestDashboardQuery requests = requestQuery.getIfAvailable();
        OrganizationSummaryResponse org = institutionId == null ? null
                : organizationService.findSummary(institutionId).orElse(null);
        NeedsUpdateRow needs = institutionId == null ? new NeedsUpdateRow(0, 0, 0)
                : equipmentService.countNeedsUpdate(institutionId, UPDATE_INTERVAL_DAYS);
        return new InstitutionSummary(u.role(), DashboardView.PROVIDER,
                org == null ? null : new InstitutionRef(org.id(), org.name()),
                counts(requests == null ? null : () -> requests.countByInstitution(institutionId)),
                requests == null ? List.<RequestActionItem>of() : requests.findPendingReview(institutionId, LIST_LIMIT),
                requests == null ? List.<UpcomingReservationItem>of()
                        : requests.findUpcomingReservations(institutionId, LIST_LIMIT),
                new NeedsUpdate(needs.total(), needs.notConfirmed(), needs.outdated()),
                overview(), new DataAvailable(requests != null, null));
    }

    private AdminSummary adminSummary() {
        RequestDashboardQuery requests = requestQuery.getIfAvailable();
        List<InstitutionVerificationRow> rows = equipmentService.countVerificationByInstitution();
        return new AdminSummary(UserRole.ADMIN, DashboardView.ADMIN,
                userService.countByStatus(UserStatus.PENDING),
                organizationService.countUnverifiedInstitutions(),
                counts(requests == null ? null : requests::countAll),
                institutionStats(rows), new EquipmentOverview(total(rows), byRegion()),
                new DataAvailable(requests != null, null));
    }

    /** 의뢰 상태 10개를 모두 넣고 없는 상태는 0 (구현이 없으면 전부 0) */
    private static Map<RequestStatus, Long> counts(Supplier<Map<RequestStatus, Long>> source) {
        Map<RequestStatus, Long> result = new EnumMap<>(RequestStatus.class);
        for (RequestStatus s : RequestStatus.values()) {
            result.put(s, 0L);
        }
        if (source != null) {
            Map<RequestStatus, Long> loaded = source.get();
            if (loaded != null) {
                loaded.forEach((status, n) -> result.put(status, n == null ? 0L : n));
            }
        }
        return result;
    }

    private EquipmentOverview overview() {
        return new EquipmentOverview(total(), byRegion());
    }

    private long total() {
        return equipmentService.countByInstitution().values().stream().mapToLong(Long::longValue).sum();
    }

    /** 기관별 집계를 이미 읽었으면 그 합 (같은 GROUP BY 를 두 번 돌리지 않도록) */
    private static long total(List<InstitutionVerificationRow> rows) {
        return rows.stream().mapToLong(InstitutionVerificationRow::equipmentCount).sum();
    }

    /** 지역 → 장비 수, 지역 가나다순 */
    private Map<String, Long> byRegion() {
        Map<String, Long> sorted = new LinkedHashMap<>();
        equipmentService.countByRegion().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(KOREAN))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /** 기관별 장비 수·기관 확인 비율 (장비 수 많은 순, 같으면 이름 가나다순) */
    private List<InstitutionStat> institutionStats(List<InstitutionVerificationRow> rows) {
        Map<Long, OrganizationSummaryResponse> orgs = organizationService.findSummaries(
                rows.stream().map(InstitutionVerificationRow::institutionId).toList());
        return rows.stream().map(r -> {
            OrganizationSummaryResponse org = orgs.get(r.institutionId());
            double rate = r.equipmentCount() == 0 ? 0
                    : Math.round(r.verifiedCount() * 1000.0 / r.equipmentCount()) / 10.0;
            return new InstitutionStat(r.institutionId(), org == null ? null : org.name(),
                    org == null ? null : org.region(), r.equipmentCount(), r.verifiedCount(), rate);
        }).sorted(Comparator.comparingLong(InstitutionStat::equipmentCount).reversed()
                .thenComparing(s -> s.name() == null ? "" : s.name(), KOREAN)).toList();
    }
}
