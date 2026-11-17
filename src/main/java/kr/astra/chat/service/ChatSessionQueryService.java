package kr.astra.chat.service;

import java.util.List;
import kr.astra.chat.dto.ChatSessionSummary;
import kr.astra.chat.repository.ChatSessionRepository;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.security.AccessGuard;
import kr.astra.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 대시보드용 조회 계약. 상담 목록 API의 페이지 응답과 생성일 정렬은 그대로 유지합니다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatSessionQueryService implements ChatSessionQuery {
    private final ChatSessionRepository sessions;

    @Override
    public List<ChatSessionSummary> findRecent(Long userId, int limit) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        CurrentUser actor = authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CurrentUser user ? user : null;
        AccessGuard.requireLogin(actor);
        AccessGuard.requireOwner(actor, userId);
        if (userId == null || userId <= 0 || limit < 1 || limit > 100)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);

        // updatedAt이 같아도 순서를 고정하며, 전체 건수 쿼리 없이 필요한 요약만 조회합니다.
        return sessions.findRecentSummaries(userId,
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "updatedAt", "id")));
    }
}
