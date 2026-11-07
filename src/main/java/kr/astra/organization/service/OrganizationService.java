package kr.astra.organization.service;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.DbConstraints;
import kr.astra.global.exception.ErrorCode;
import kr.astra.organization.dto.InstitutionOptionResponse;
import kr.astra.organization.dto.OrganizationSummaryResponse;
import kr.astra.organization.entity.OrgType;
import kr.astra.organization.entity.Organization;
import kr.astra.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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

    /** 가입 화면용: 승인된 기관만, 이름순 */
    public List<InstitutionOptionResponse> listVerifiedInstitutions() {
        return organizationRepository.findAllByOrgTypeAndVerifiedTrue(OrgType.INSTITUTION).stream()
                .sorted(BY_KOREAN_NAME)
                .map(InstitutionOptionResponse::from)
                .toList();
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
