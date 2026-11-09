package kr.astra.request.repository;

import java.util.List;
import java.util.Optional;
import kr.astra.request.entity.TestRequestRevision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRequestRevisionRepository extends JpaRepository<TestRequestRevision, Long> {

    Optional<TestRequestRevision> findByRequestIdAndRevisionNo(Long requestId, int revisionNo);

    List<TestRequestRevision> findAllByRequestIdOrderByRevisionNoAsc(Long requestId);
}
