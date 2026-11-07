package kr.astra.requirement.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import kr.astra.requirement.entity.TestRequirement;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RequirementRepository extends JpaRepository<TestRequirement, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TestRequirement r where r.id = :id")
    Optional<TestRequirement> lockById(@Param("id") Long id);
    Optional<TestRequirement> findFirstByChatSessionIdOrderByIdDesc(Long sessionId);
}
