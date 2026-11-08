package kr.astra.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.astra.equipment.dto.EquipmentDetailResponse;
import kr.astra.equipment.dto.EquipmentInputs.TransferRequest;
import kr.astra.equipment.service.EquipmentCommandService;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 · 장비", description = "장비 소속 기관 변경 (api-spec §12, 관리자 전용)")
@RestController
@RequestMapping("/admin/equipment")
@RequiredArgsConstructor
public class AdminEquipmentController {

    private final EquipmentCommandService commandService;

    @Operation(summary = "장비 소속 기관 변경", description = "잘못 수집된 장비를 승인된 다른 기관으로 옮김. 변경 이력에 TRANSFER 로 기록")
    @PostMapping("/{id}/transfer")
    public ApiResponse<EquipmentDetailResponse> transfer(@AuthenticationPrincipal CurrentUser user,
                                                         @PathVariable Long id,
                                                         @Valid @RequestBody TransferRequest request) {
        return ApiResponse.ok(commandService.transfer(user, id, request.institutionId(), request.reason()));
    }
}
