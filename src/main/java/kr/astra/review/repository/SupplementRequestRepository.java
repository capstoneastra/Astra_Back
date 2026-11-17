package kr.astra.review.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kr.astra.review.entity.SupplementRequest;
import kr.astra.review.entity.SupplementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplementRequestRepository extends JpaRepository<SupplementRequest, Long> {

    boolean existsByRequestIdAndStatus(Long requestId, SupplementStatus status);

    Optional<SupplementRequest> findFirstByRequestIdAndStatus(Long requestId, SupplementStatus status);

    List<SupplementRequest> findAllByRequestIdOrderByCreatedAtDescIdDesc(Long requestId);

    List<SupplementRequest> findAllByRequestIdInAndStatus(Collection<Long> requestIds, SupplementStatus status);

    List<SupplementRequest> findAllByRequestIdAndStatus(Long requestId, SupplementStatus status);

    /** 의뢰를 잠그기 전에 의뢰 id 만 조회 (엔티티를 미리 읽어 두지 않으려고) */
    @Query("SELECT s.requestId FROM SupplementRequest s WHERE s.id = :id")
    Optional<Long> findRequestIdById(@Param("id") Long id);
}
