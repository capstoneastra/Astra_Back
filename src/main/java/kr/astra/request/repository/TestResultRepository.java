package kr.astra.request.repository;

import java.util.Optional;
import kr.astra.request.entity.TestResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestResultRepository extends JpaRepository<TestResult, Long> {

    Optional<TestResult> findByRequestId(Long requestId);

    boolean existsByRequestId(Long requestId);
}
