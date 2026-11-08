package kr.astra.chat.dto;

import java.time.OffsetDateTime;

/** 최근 상담 한 줄 (ChatSessionQuery 결과, api-spec §10 recentChats) */
public record ChatSessionSummary(Long id, String title, OffsetDateTime updatedAt) {
}
