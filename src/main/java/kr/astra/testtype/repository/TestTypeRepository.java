package kr.astra.testtype.repository;

import java.util.List;
import kr.astra.testtype.entity.TestType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestTypeRepository extends JpaRepository<TestType, String> {

    List<TestType> findAllByOrderByDisplayOrderAscCodeAsc();
}
