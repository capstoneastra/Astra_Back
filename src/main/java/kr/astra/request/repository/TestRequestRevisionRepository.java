package kr.astra.request.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import kr.astra.request.entity.TestRequestRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestRequestRevisionRepository extends JpaRepository<TestRequestRevision, Long> {

    Optional<TestRequestRevision> findByRequestIdAndRevisionNo(Long requestId, int revisionNo);

    List<TestRequestRevision> findAllByRequestIdOrderByRevisionNoAsc(Long requestId);

    /** 제출본 이력 목록용 (본문 content 는 읽지 않음) */
    @Query("""
            SELECT new kr.astra.request.repository.TestRequestRevisionRepository$Meta(
                   v.revisionNo, v.aiGenerated, v.submitted, v.changeSummary, v.authorId, v.createdAt, v.submittedAt)
            FROM TestRequestRevision v WHERE v.requestId = :requestId ORDER BY v.revisionNo
            """)
    List<Meta> findMetaByRequestId(@Param("requestId") Long requestId);

    record Meta(int revisionNo, boolean aiGenerated, boolean submitted, String changeSummary, Long authorId,
                OffsetDateTime createdAt, OffsetDateTime submittedAt) {
    }
}
