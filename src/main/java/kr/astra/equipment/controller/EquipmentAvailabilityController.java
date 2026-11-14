package kr.astra.equipment.controller;

import java.time.*;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.astra.equipment.service.EquipmentScheduleService;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;

@RestController @RequiredArgsConstructor @RequestMapping("/equipment/{id}")
public class EquipmentAvailabilityController {
    private final EquipmentScheduleService schedules;
    public record DowntimeInput(@NotNull OffsetDateTime startAt, @NotNull OffsetDateTime endAt,
                                @NotBlank @Size(max=500) String reason) {}
    @GetMapping("/availability") @Operation(summary="확정 예약·정비 일정·상태를 합친 기간별 가용성")
    public ApiResponse<EquipmentScheduleService.Calendar> get(@PathVariable Long id,
            @RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to) {
        return ApiResponse.ok(schedules.availability(id,from,to));
    }
    @PostMapping("/downtimes") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @Operation(summary="소유기관의 정비·운휴 일정 등록")
    public ApiResponse<Map<String,Long>> add(@AuthenticationPrincipal CurrentUser actor,@PathVariable Long id,
                                              @Valid @RequestBody DowntimeInput input) {
        return ApiResponse.ok(Map.of("id",schedules.addDowntime(actor,id,input.startAt(),input.endAt(),input.reason())));
    }
    @GetMapping("/downtimes") @Operation(summary="소유기관의 정비·운휴 일정과 사유 조회")
    public ApiResponse<java.util.List<kr.astra.equipment.repository.EquipmentDowntimeRepository.Downtime>> list(
            @AuthenticationPrincipal CurrentUser actor,@PathVariable Long id,
            @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) {
        return ApiResponse.ok(schedules.downtimes(actor,id,from,to));
    }
    @DeleteMapping("/downtimes/{downtimeId}") @Operation(summary="소유기관의 정비·운휴 일정 삭제")
    public ApiResponse<Void> delete(@AuthenticationPrincipal CurrentUser actor,@PathVariable Long id,@PathVariable Long downtimeId) {
        schedules.deleteDowntime(actor,id,downtimeId); return ApiResponse.ok(null);
    }
}
