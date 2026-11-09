package kr.astra.review.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kr.astra.review.entity.SupplementRequest;
import kr.astra.review.entity.SupplementStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplementRequestRepository extends JpaRepository<SupplementRequest, Long> {

    boolean existsByRequestIdAndStatus(Long requestId, SupplementStatus status);

    Optional<SupplementRequest> findFirstByRequestIdAndStatus(Long requestId, SupplementStatus status);

    List<SupplementRequest> findAllByRequestIdOrderByCreatedAtDescIdDesc(Long requestId);

    List<SupplementRequest> findAllByRequestIdInAndStatus(Collection<Long> requestIds, SupplementStatus status);

    List<SupplementRequest> findAllByRequestIdAndStatus(Long requestId, SupplementStatus status);
}
