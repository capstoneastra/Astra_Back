package kr.astra.organization.repository;

import java.util.List;
import kr.astra.organization.entity.OrgType;
import kr.astra.organization.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    boolean existsByOrgTypeAndName(OrgType orgType, String name);

    List<Organization> findAllByOrgTypeAndVerifiedTrue(OrgType orgType);
}
