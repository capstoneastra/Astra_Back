package kr.astra.request.repository;

import java.util.List;
import kr.astra.request.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findAllByRequestIdOrderByIdAsc(Long requestId);
}
