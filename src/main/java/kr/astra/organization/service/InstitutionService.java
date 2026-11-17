package kr.astra.organization.service;

import java.util.List;
import java.util.Map;
import kr.astra.equipment.entity.EquipmentType;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.organization.dto.InstitutionDetailResponse;
import kr.astra.organization.dto.InstitutionInfo;
import kr.astra.organization.dto.InstitutionSummaryResponse;
import kr.astra.testtype.service.TestTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장비 보유 조직 목록·상세 (api-spec §2 GET /institutions). 승인된 기관·대학은 모두,
 * 기업은 활성 장비를 1대 이상 등록한 경우에만 보여줍니다 (장비가 없는 기업 정보는 공개하지 않음).
 * 장비 수가 필요해서 OrganizationService 와 따로 둡니다 (EquipmentService 가 OrganizationService 를 쓰므로 순환 방지).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InstitutionService {

    private final OrganizationService organizationService;
    private final EquipmentService equipmentService;
    private final TestTypeService testTypeService;

    public List<InstitutionSummaryResponse> list() {
        Map<Long, Long> counts = equipmentService.countByInstitution();
        return organizationService.listVerifiedEquipmentOwners().stream()
                .filter(info -> info.orgType().isInstitutionType() || counts.getOrDefault(info.id(), 0L) > 0)
                .map(info -> InstitutionSummaryResponse.of(info, counts.getOrDefault(info.id(), 0L)))
                .toList();
    }

    /** 없거나 미승인이거나, 장비가 없는 기업이면 404 NOT_FOUND */
    public InstitutionDetailResponse getDetail(Long id) {
        InstitutionInfo info = organizationService.findVerifiedEquipmentOwner(id).orElseThrow(InstitutionService::notFound);
        Map<EquipmentType, Long> byType = equipmentService.countByEquipmentType(id);
        if (!info.orgType().isInstitutionType() && byType.isEmpty()) {
            throw notFound();
        }
        Map<String, Long> byTestType = equipmentService.countByTestTypeForInstitution(id);
        List<InstitutionDetailResponse.TestTypeCount> testTypes = testTypeService.findAllOrdered().stream()
                .filter(t -> byTestType.containsKey(t.getCode()))
                .map(t -> new InstitutionDetailResponse.TestTypeCount(t.getCode(), t.getNameKo(),
                        byTestType.get(t.getCode())))
                .toList();
        long total = byType.values().stream().mapToLong(Long::longValue).sum();
        return new InstitutionDetailResponse(info.id(), info.orgType(), info.name(), info.parentName(), info.region(),
                info.address(), info.phone(), info.email(), info.homepageUrl(), total, byType, testTypes);
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "기관을 찾을 수 없습니다.", null);
    }
}
