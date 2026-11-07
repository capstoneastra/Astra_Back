package kr.astra.matching.repository;
import kr.astra.matching.entity.MatchRun;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MatchRunRepository extends JpaRepository<MatchRun, Long> {
    Page<MatchRun> findByRequirementId(Long requirementId, Pageable pageable);
}
