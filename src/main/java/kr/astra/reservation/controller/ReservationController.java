package kr.astra.reservation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.security.CurrentUser;
import kr.astra.request.dto.RequestInputs;
import kr.astra.reservation.dto.ReservationInputs;
import kr.astra.reservation.dto.ReservationResponses.BusySlot;
import kr.astra.reservation.dto.ReservationResponses.ReservationResult;
import kr.astra.reservation.dto.ReservationResponses.ReservationView;
import kr.astra.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "예약", description = "일정 제안·대안 제안·확정·취소, 장비 예약 현황 (api-spec §7). "
        + "같은 장비의 확정 일정과 겹치면 409 RESERVATION_OVERLAP (details.conflicts), 시간 구간은 [시작, 종료)")
@RestController
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @Operation(summary = "일정 제안", description = "SCHEDULING 상태에서 기업·기관 모두. 상대편에게 알림")
    @PostMapping("/test-requests/{id}/reservations")
    public ResponseEntity<ApiResponse<ReservationResult>> propose(@AuthenticationPrincipal CurrentUser user,
                                                                  @PathVariable Long id,
                                                                  @Valid @RequestBody ReservationInputs.Propose request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(reservationService.propose(user, id, request)));
    }

    @Operation(summary = "의뢰의 일정 목록", description = "제안·대안·확정·취소 전부, 최신순")
    @GetMapping("/test-requests/{id}/reservations")
    public ApiResponse<List<ReservationView>> list(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        return ApiResponse.ok(reservationService.listForRequest(user, id));
    }

    @Operation(summary = "대안 일정 제안", description = "원래 제안은 CANCELED, 새 일정은 COUNTER_PROPOSED. 상대편에게 알림")
    @PostMapping("/reservations/{id}/counter")
    public ResponseEntity<ApiResponse<ReservationResult>> counter(@AuthenticationPrincipal CurrentUser user,
                                                                  @PathVariable Long id,
                                                                  @Valid @RequestBody ReservationInputs.Propose request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(reservationService.counter(user, id, request)));
    }

    @Operation(summary = "예약 확정", description = "장비 보유 조직 구성원. 의뢰 SCHEDULING → CONFIRMED, 의뢰자에게 알림. "
            + "이미 확정된 의뢰면 409 RESERVATION_ALREADY_CONFIRMED")
    @PostMapping("/reservations/{id}/confirm")
    public ApiResponse<ReservationResult> confirm(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
                                                  @RequestBody(required = false) RequestInputs.VersionOnly request) {
        return ApiResponse.ok(reservationService.confirm(user, id, request == null ? null : request.version()));
    }

    @Operation(summary = "예약 취소", description = "확정 전 제안만 (확정된 일정은 409 REQUEST_INVALID_STATUS)")
    @PostMapping("/reservations/{id}/cancel")
    public ApiResponse<ReservationResult> cancel(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
                                                 @RequestBody(required = false) RequestInputs.VersionOnly request) {
        return ApiResponse.ok(reservationService.cancel(user, id, request == null ? null : request.version()));
    }

    @Operation(summary = "장비 예약 현황", description = "확정된 바쁜 구간만 (from·to 는 한국 날짜, to 포함). 기본 오늘부터 60일")
    @GetMapping("/equipment/{id}/reservations")
    public ApiResponse<List<BusySlot>> busy(@PathVariable Long id,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reservationService.busy(id, from, to));
    }
}
