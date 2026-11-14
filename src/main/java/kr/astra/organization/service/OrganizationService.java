package kr.astra.organization.service;

import java.text.Collator;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.DbConstraints;
import kr.astra.global.exception.ErrorCode;
import kr.astra.organization.dto.AdminOrganizationResponse;
import kr.astra.organization.dto.InstitutionInfo;
import kr.astra.organization.dto.InstitutionOptionResponse;
import kr.astra.organization.dto.OrganizationAdminResponse;
import kr.astra.organization.dto.OrganizationSummaryResponse;
import kr.astra.organization.entity.OrgType;
import kr.astra.organization.entity.Organization;
import kr.astra.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조직(organizations) 조회·생성. 다른 도메인은 이 서비스만 사용합니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrganizationService {

    /** DB 정렬(collation)에 따라 한글 순서가 달라지므로 이름순 정렬은 자바에서 한국어 기준으로 */
    private static final Comparator<Organization> BY_KOREAN_NAME =
            Comparator.comparing(Organization::getName, Collator.getInstance(Locale.KOREAN));

    private final OrganizationRepository organizationRepository;

    public record SecurityInfo(Boolean hasSecureFacility, String securityNote) {}
    public SecurityInfo security(Long id) {
        var org = findOrganization(id);
        if (!org.isInstitution() || !org.isVerified()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return new SecurityInfo(org.getHasSecureFacility(), org.getSecurityNote());
    }
    @Transactional
    public SecurityInfo updateSecurity(kr.astra.global.security.CurrentUser actor, Long id, Boolean facility, String note) {
        kr.astra.global.security.AccessGuard.requireInstitution(actor,id);
        var org = findOrganization(id);
        if (!org.isInstitution()) throw new BusinessException(ErrorCode.NOT_FOUND);
        org.updateSecurity(facility,note);
        return new SecurityInfo(org.getHasSecureFacility(),org.getSecurityNote());
    }

    /** 가입 화면용: 승인된 기관만, 이름순 */
    public List<InstitutionOptionResponse> listVerifiedInstitutions() {
        return organizationRepository.findAllByOrgTypeAndVerifiedTrue(OrgType.INSTITUTION).stream()
                .sorted(BY_KOREAN_NAME)
                .map(InstitutionOptionResponse::from)
                .toList();
    }

    /** 승인된 기관 전체 기본 정보 (이름 가나다순) */
    public List<InstitutionInfo> listVerifiedInstitutionInfos() {
        return organizationRepository.findAllByOrgTypeAndVerifiedTrue(OrgType.INSTITUTION).stream()
                .sorted(BY_KOREAN_NAME)
                .map(InstitutionInfo::from)
                .toList();
    }

    /** 승인된 기관 하나. 없거나 기관이 아니거나 미승인이면 empty */
    public Optional<InstitutionInfo> findVerifiedInstitution(Long organizationId) {
        return organizationRepository.findById(organizationId)
                .filter(org -> org.isInstitution() && org.isVerified())
                .map(InstitutionInfo::from);
    }

    /** id → 조직 요약 (이름·지역 등). 없는 id 는 빠짐 */
    public Map<Long, OrganizationSummaryResponse> findSummaries(Collection<Long> organizationIds) {
        return organizationRepository.findAllById(organizationIds).stream()
                .map(OrganizationSummaryResponse::from)
                .collect(Collectors.toMap(OrganizationSummaryResponse::id, Function.identity()));
    }

    /** 승인 대기 중인 기관 수 (관리자 대시보드) */
    public long countUnverifiedInstitutions() {
        return organizationRepository.countByOrgTypeAndVerifiedFalse(OrgType.INSTITUTION);
    }

    /** 승인된 기관이면 true (기관 담당자가 기존 기관을 골라 가입할 때 확인) */
    public boolean isVerifiedInstitution(Long organizationId) {
        return organizationRepository.findById(organizationId)
                .filter(org -> org.isInstitution() && org.isVerified())
                .isPresent();
    }

    public Optional<OrganizationSummaryResponse> findSummary(Long organizationId) {
        if (organizationId == null) {
            return Optional.empty();
        }
        return organizationRepository.findById(organizationId).map(OrganizationSummaryResponse::from);
    }

    /** 관리자 화면용 조직 정보 (사업자번호·주소·연락처 포함). id → 정보 */
    public Map<Long, OrganizationAdminResponse> findAdminViews(Collection<Long> organizationIds) {
        return organizationRepository.findAllById(organizationIds).stream()
                .map(OrganizationAdminResponse::from)
                .collect(Collectors.toMap(OrganizationAdminResponse::id, Function.identity()));
    }

    /** 회원 가입을 승인할 때 그 회원이 가입하며 만든 조직(기업·신규 기관)도 승인. 이미 승인됐으면 그대로 */
    @Transactional
    public void verify(Long organizationId) {
        if (organizationId != null) {
            organizationRepository.findById(organizationId).ifPresent(Organization::verify);
        }
    }

    /** 같은 유형·이름의 미승인 조직 id (거절된 가입 신청이 남긴 조직 재사용 판단용) */
    public Optional<Long> findUnverifiedId(OrgType orgType, String name) {
        return organizationRepository.findByOrgTypeAndName(orgType, name.trim())
                .filter(org -> !org.isVerified())
                .map(Organization::getId);
    }

    /** 거절된 기업 가입 신청의 조직을 다시 신청에 사용: 사업자등록번호만 새 입력으로 */
    @Transactional
    public Long reapplyCompany(Long organizationId, String businessRegNo) {
        Organization org = organizationRepository.findById(organizationId).orElseThrow();
        org.reapplyCompany(businessRegNo);
        return org.getId();
    }

    /** 거절된 기관 가입 신청의 조직을 다시 신청에 사용: 주소·연락처를 새 입력으로 */
    @Transactional
    public Long reapplyInstitution(Long organizationId, String address, String phone) {
        Organization org = organizationRepository.findById(organizationId).orElseThrow();
        org.reapplyInstitution(address, phone);
        return org.getId();
    }

    /** 관리자 조직 목록에서 정렬할 수 있는 필드 */
    private static final Set<String> ADMIN_SORTABLE = Set.of("createdAt", "id");

    /** 관리자 조직 목록 (api-spec §12). 정렬은 createdAt·id 만, 그 외 400 */
    public Page<AdminOrganizationResponse> searchForAdmin(OrgType orgType, Boolean verified, Pageable pageable) {
        pageable.getSort().forEach(order -> {
            if (!ADMIN_SORTABLE.contains(order.getProperty())) {
                String reason = "정렬할 수 없는 필드입니다. 가능: createdAt, id";
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, reason,
                        Map.of("fields", List.of(Map.of("field", "sort", "reason", reason))));
            }
        });
        return organizationRepository.search(orgType, verified, pageable).map(AdminOrganizationResponse::from);
    }

    public AdminOrganizationResponse getForAdmin(Long organizationId) {
        return AdminOrganizationResponse.from(findOrganization(organizationId));
    }

    /** 관리자 기관 직접 등록 (바로 승인). 같은 이름의 기관이 있으면 409 ORGANIZATION_ALREADY_EXISTS */
    @Transactional
    public AdminOrganizationResponse createInstitutionByAdmin(String name, String parentName, String region,
                                                              String address, String phone, String email,
                                                              String homepageUrl) {
        Long id = save(Organization.newInstitutionByAdmin(name.trim(), parentName, region, address, phone, email,
                homepageUrl));
        return getForAdmin(id);
    }

    /** 관리자 승인 (이미 승인됐으면 그대로). 없으면 404 NOT_FOUND */
    @Transactional
    public AdminOrganizationResponse verifyByAdmin(Long organizationId) {
        Organization org = findOrganization(organizationId);
        org.verify();
        organizationRepository.flush();
        return AdminOrganizationResponse.from(org);
    }

    /** 관리자 수정: 보낸 값만. 이름을 바꿔 같은 유형에 같은 이름이 생기면 409 ORGANIZATION_ALREADY_EXISTS */
    @Transactional
    public AdminOrganizationResponse updateByAdmin(Long organizationId, String name, String parentName,
                                                   String businessRegNo, String region, String address, String phone,
                                                   String email, String homepageUrl) {
        Organization org = findOrganization(organizationId);
        String newName = name == null ? null : name.trim();
        if (newName != null && !newName.equals(org.getName())
                && organizationRepository.existsByOrgTypeAndName(org.getOrgType(), newName)) {
            throw new BusinessException(ErrorCode.ORGANIZATION_ALREADY_EXISTS);
        }
        org.update(newName, parentName, businessRegNo, region, address, phone, email, homepageUrl);
        try {
            organizationRepository.flush();
        } catch (DataIntegrityViolationException e) {
            if (DbConstraints.UNIQUE_VIOLATION.equals(DbConstraints.sqlState(e))) {
                throw new BusinessException(ErrorCode.ORGANIZATION_ALREADY_EXISTS);
            }
            throw e;
        }
        return AdminOrganizationResponse.from(org);
    }

    private Organization findOrganization(Long organizationId) {
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "조직을 찾을 수 없습니다.", null));
    }

    /** 기업 회원가입: 기업 조직 생성. 같은 이름의 기업이 있으면 409 ORGANIZATION_ALREADY_EXISTS */
    @Transactional
    public Long createCompany(String name, String businessRegNo) {
        return save(Organization.newCompany(name.trim(), businessRegNo));
    }

    /** 미등록 기관 담당자 가입: 승인 대기(is_verified=false) 기관 생성 */
    @Transactional
    public Long createInstitutionApplication(String name, String address, String phone) {
        return save(Organization.newInstitutionApplication(name.trim(), address, phone));
    }

    private Long save(Organization org) {
        if (organizationRepository.existsByOrgTypeAndName(org.getOrgType(), org.getName())) {
            throw new BusinessException(ErrorCode.ORGANIZATION_ALREADY_EXISTS);
        }
        try {
            return organizationRepository.saveAndFlush(org).getId();
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 이름으로 가입한 경우 UNIQUE(org_type, name) 이 최종으로 막음
            if (DbConstraints.UNIQUE_VIOLATION.equals(DbConstraints.sqlState(e))) {
                throw new BusinessException(ErrorCode.ORGANIZATION_ALREADY_EXISTS);
            }
            throw e;
        }
    }
}
