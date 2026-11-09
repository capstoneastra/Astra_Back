package kr.astra.chat.repository;
import java.util.Optional;
import java.util.List;
import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;
import kr.astra.chat.dto.ChatSessionSummary;
import kr.astra.chat.entity.ChatSession;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ChatSessionRepository extends JpaRepository<ChatSession,Long> {
    Page<ChatSession> findByUserId(Long userId, Pageable pageable);
    @Query("select new kr.astra.chat.dto.ChatSessionSummary(s.id, s.title, s.updatedAt) from ChatSession s where s.userId = :userId")
    List<ChatSessionSummary> findRecentSummaries(@Param("userId") Long userId, Pageable pageable);
    /** 세션 잠금을 획득한 쓰기 트랜잭션 안에서만 호출합니다. 공용 BaseTimeEntity는 변경하지 않습니다. */
    @Modifying(flushAutomatically = true)
    @Query("update ChatSession s set s.updatedAt = :activityAt where s.id = :id")
    void recordActivity(@Param("id") Long id, @Param("activityAt") OffsetDateTime activityAt);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from ChatSession s where s.id = :id")
    Optional<ChatSession> lockById(@Param("id") Long id);
}
