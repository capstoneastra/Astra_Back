package kr.astra.chat.repository;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import kr.astra.chat.entity.ChatSession;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ChatSessionRepository extends JpaRepository<ChatSession,Long> {
    Page<ChatSession> findByUserId(Long userId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from ChatSession s where s.id = :id")
    Optional<ChatSession> lockById(@Param("id") Long id);
}
