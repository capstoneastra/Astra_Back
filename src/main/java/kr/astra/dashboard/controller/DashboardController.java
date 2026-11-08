package kr.astra.dashboard.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.astra.dashboard.dto.DashboardResponses.EquipmentStats;
import kr.astra.dashboard.dto.DashboardResponses.Summary;
import kr.astra.dashboard.service.DashboardService;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "대시보드", description = "역할별 요약, 장비 통계 (api-spec §10, 기능 14)")
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "역할별 요약", description = "기업: 내 의뢰 상태별 수·처리할 의뢰·최근 상담 / "
            + "기관: 접수함 상태별 수·검토 대기·다가오는 예약·갱신 필요 장비 수 / 관리자: 승인 대기 회원·기관 수·전체 의뢰·기관별 장비 확인 비율. "
            + "의뢰(BE-B)·상담(BE-C) 칸은 구현 전까지 0·빈 목록이고 dataAvailable 이 false")
    @GetMapping("/summary")
    public ApiResponse<Summary> summary(@AuthenticationPrincipal CurrentUser user) {
        return ApiResponse.ok(dashboardService.summary(user));
    }

    @Operation(summary = "장비 통계", description = "전체 장비 수, 지역별·장비 유형별·시험유형별 장비 수, 기관별 보유·기관 확인 비율")
    @GetMapping("/equipment-stats")
    public ApiResponse<EquipmentStats> equipmentStats() {
        return ApiResponse.ok(dashboardService.equipmentStats());
    }
}
