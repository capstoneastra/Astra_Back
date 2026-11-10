package kr.astra.request.repository;

import java.util.List;
import kr.astra.request.entity.RequestStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestStatusHistoryRepository extends JpaRepository<RequestStatusHistory, Long> {

    List<RequestStatusHistory> findAllByRequestIdOrderByCreatedAtAscIdAsc(Long requestId);
}
