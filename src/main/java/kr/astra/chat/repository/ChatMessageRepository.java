package kr.astra.chat.repository;
import java.util.*;
import kr.astra.chat.entity.ChatMessage;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ChatMessageRepository extends JpaRepository<ChatMessage,Long> {
    Page<ChatMessage> findBySessionId(Long sessionId, Pageable pageable);
    List<ChatMessage> findTop101BySessionIdOrderByIdDesc(Long sessionId);
    Optional<ChatMessage> findFirstBySessionIdOrderByIdDesc(Long sessionId);
}
