package kr.astra.organization.repository;

import java.util.List;
import java.util.Optional;
import kr.astra.organization.entity.OrgType;
import kr.astra.organization.entity.Organization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    boolean existsByOrgTypeAndName(OrgType orgType, String name);

    Optional<Organization> findByOrgTypeAndName(OrgType orgType, String name);

    List<Organization> findAllByOrgTypeAndVerifiedTrue(OrgType orgType);

    long countByOrgTypeAndVerifiedFalse(OrgType orgType);

    /** 관리자 조직 목록: orgType·verified 는 null 이면 조건 없음 */
    @Query("""
            SELECT o FROM Organization o
            WHERE (:orgType IS NULL OR o.orgType = :orgType)
              AND (:verified IS NULL OR o.verified = :verified)
            """)
    Page<Organization> search(@Param("orgType") OrgType orgType, @Param("verified") Boolean verified,
                              Pageable pageable);
}
