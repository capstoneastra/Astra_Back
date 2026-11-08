package kr.astra.chat.service;

import java.util.List;
import kr.astra.chat.dto.ChatSessionSummary;

/**
 * [BE-C 구현 → BE-A 사용] 대시보드(GET /dashboard/summary, api-spec §10 recentChats)용 최근 상담 목록.
 * <p>
 * BE-A 는 {@code ObjectProvider<ChatSessionQuery>} 로 받아서, 구현이 없으면 빈 목록으로 응답합니다.
 * 구현 클래스에 {@code @Service} 를 붙이면 대시보드에 자동으로 채워집니다. 시그니처를 바꾸려면 BE-A 와 먼저 맞춰 주세요.
 */
public interface ChatSessionQuery {

    /**
     * 사용자의 최근 상담 (updated_at 최신순, 같은 시각이면 id 내림차순).
     * 인증된 기업 본인 또는 ADMIN이 호출합니다. 목록이 비어 있어도 구현 연결은 유효합니다.
     *
     * @param limit 최대 개수 (1~100, 대시보드는 5)
     */
    List<ChatSessionSummary> findRecent(Long userId, int limit);
}
