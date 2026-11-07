package kr.astra.aipipeline.repository;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import kr.astra.aipipeline.entity.PipelineRun;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface PipelineRepository extends JpaRepository<PipelineRun,Long> {
    java.util.List<PipelineRun> findTop100ByIdGreaterThanAndStatusInOrderByIdAsc(Long afterId,
            java.util.Collection<kr.astra.aipipeline.entity.PipelineStatus> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from PipelineRun r where r.id = :id")
    Optional<PipelineRun> lockById(@Param("id") Long id);
}
