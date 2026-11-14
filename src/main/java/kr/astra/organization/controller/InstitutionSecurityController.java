package kr.astra.organization.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.security.CurrentUser;
import kr.astra.organization.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;

@RestController @RequiredArgsConstructor @RequestMapping("/institutions/{id}/security")
public class InstitutionSecurityController {
    private final OrganizationService organizations;
    public record Input(Boolean hasSecureFacility, @Size(max=1000) String securityNote) {}
    @GetMapping @Operation(summary="기관 보안시설·시료/데이터 반출·NDA 협의 안내")
    public ApiResponse<OrganizationService.SecurityInfo> get(@PathVariable Long id) {
        return ApiResponse.ok(organizations.security(id));
    }
    @PatchMapping @Operation(summary="소유기관 또는 관리자의 보안 협의 정보 수정")
    public ApiResponse<OrganizationService.SecurityInfo> update(@AuthenticationPrincipal CurrentUser actor,
            @PathVariable Long id,@Valid @RequestBody Input input) {
        return ApiResponse.ok(organizations.updateSecurity(actor,id,input.hasSecureFacility(),input.securityNote()));
    }
}
