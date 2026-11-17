package kr.astra.chat.service;

import java.time.OffsetDateTime;
import java.util.*;
import kr.astra.chat.dto.ChatResponse;
import kr.astra.chat.entity.*;
import kr.astra.chat.repository.*;
import kr.astra.global.exception.*;
import kr.astra.global.response.PageResponse;
import kr.astra.global.security.*;
import kr.astra.requirement.dto.*;
import kr.astra.requirement.service.RequirementApplicationService;
import kr.astra.llm.service.ConditionExtractionService.Extraction;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class ChatPersistence {
    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final RequirementApplicationService requirements;
    /** @param knownTestType 세션의 기존 시험조건 유형, 없으면 직전 AI 응답의 suggestedTestType (둘 다 없으면 null) */
    public record Transcript(Long ownerId, Long lastMessageId, List<ChatResponse.Message> messages, String userText,
            String knownTestType) {
        public Transcript(Long ownerId, Long lastMessageId, List<ChatResponse.Message> messages, String userText) {
            this(ownerId, lastMessageId, messages, userText, null);
        }
    }
    public record Turn(Long sessionId, ChatResponse.Message userMessage, Transcript transcript) {}

    @Transactional
    public ChatResponse.Session create(CurrentUser user, String title) {
        AccessGuard.requireLogin(user);
        return ChatResponse.Session.of(sessions.saveAndFlush(new ChatSession(user.userId(),title)));
    }
    public PageResponse<ChatResponse.Session> list(CurrentUser user, Pageable page) {
        AccessGuard.requireLogin(user);
        return PageResponse.from(sessions.findByUserId(user.userId(),page(page,"createdAt",Sort.Direction.DESC)), ChatResponse.Session::of);
    }
    public PageResponse<ChatResponse.Message> messages(CurrentUser user, Long sessionId, Pageable page) {
        owned(user,sessionId,false);
        return PageResponse.from(messages.findBySessionId(sessionId,page(page,"id",Sort.Direction.ASC)),ChatResponse.Message::of);
    }
    public Transcript transcript(CurrentUser user, Long id) {
        ChatSession s = owned(user,id,false);
        List<ChatMessage> history = new ArrayList<>(messages.findTop101BySessionIdOrderByIdDesc(id));
        if (history.size() > 100) throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "대화가 너무 깁니다. 새 상담을 시작해 주세요.",null);
        Collections.reverse(history);
        Long lastId = history.isEmpty() ? null : history.getLast().getId(); // 동시 변경 확인은 걸러내기 전 마지막 ID 로
        // 응답을 못 받은(FAILED) 질문은 재전송 시 같은 문장이 중복되므로 추출·상담 입력에서 뺌
        List<ChatMessage> usable = history.stream().filter(m -> !failed(m)).toList();
        String text = usable.stream().filter(m -> m.getRole() == ChatRole.USER).map(ChatMessage::getContent)
                .collect(java.util.stream.Collectors.joining("\n"));
        if (text.length() > 50000) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "대화가 너무 깁니다. 새 상담을 시작해 주세요.",null);
        return new Transcript(s.getUserId(),lastId,usable.stream().map(ChatResponse.Message::of).toList(),text,
                knownTestType(user,id,usable));
    }
    private static boolean failed(ChatMessage m) {
        return m.getRole() == ChatRole.USER && m.getMetadata() != null && "FAILED".equals(m.getMetadata().get("state"));
    }
    /** 기존 시험조건의 유형이 우선 (다른 유형이 추출되어 저장이 막히지 않게), 없으면 직전 AI 응답이 제안한 유형 */
    private String knownTestType(CurrentUser user, Long sessionId, List<ChatMessage> history) {
        var existing = requirements.forSession(user,sessionId);
        if (existing != null && existing.isPresent()) return existing.get().testTypeCode();
        for (ChatMessage m : history.reversed()) {
            if (m.getRole() != ChatRole.ASSISTANT || m.getMetadata() == null) continue;
            return m.getMetadata().get("suggestedTestType") instanceof String type && !type.isBlank() ? type : null;
        }
        return null;
    }
    @Transactional
    public Turn begin(CurrentUser user, Long id, String content) {
        ChatSession s = owned(user,id,true);
        if (s.getStatus() != ChatSessionStatus.ACTIVE) throw new BusinessException(ErrorCode.REQUEST_INVALID_STATUS);
        messages.findFirstBySessionIdOrderByIdDesc(id).ifPresent(last -> {
            if (last.getRole() == ChatRole.USER && last.getMetadata() != null && "PENDING".equals(last.getMetadata().get("state"))) {
                if (last.getCreatedAt().isAfter(OffsetDateTime.now().minusMinutes(3)))
                    throw new BusinessException(ErrorCode.VERSION_CONFLICT,"이 상담의 응답을 생성 중입니다.",null);
                last.mark("FAILED");
            }
        });
        ChatMessage message = messages.saveAndFlush(new ChatMessage(id,ChatRole.USER,content,Map.of("state","PENDING")));
        sessions.recordActivity(id, OffsetDateTime.now());
        return new Turn(id,ChatResponse.Message.of(message),transcript(user,id));
    }
    @Transactional
    public ChatResponse.Exchange complete(CurrentUser user, Turn turn, String answer, Map<String,Object> metadata) {
        owned(user,turn.sessionId(),true);
        ChatMessage original = messages.findById(turn.userMessage().id()).orElseThrow();
        ChatMessage last = messages.findFirstBySessionIdOrderByIdDesc(turn.sessionId()).orElseThrow();
        if (!last.getId().equals(original.getId()) || !"PENDING".equals(original.getMetadata().get("state")))
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        original.mark("DONE");
        ChatMessage assistant = messages.saveAndFlush(new ChatMessage(turn.sessionId(),ChatRole.ASSISTANT,answer,metadata));
        sessions.recordActivity(turn.sessionId(), OffsetDateTime.now());
        return new ChatResponse.Exchange(ChatResponse.Message.of(original),ChatResponse.Message.of(assistant));
    }
    @Transactional
    public void fail(CurrentUser user, Turn turn) {
        owned(user,turn.sessionId(),true);
        messages.findById(turn.userMessage().id()).ifPresent(m -> {
            if (m.getMetadata() != null && "PENDING".equals(m.getMetadata().get("state"))) m.mark("FAILED");
        });
    }
    @Transactional
    public RequirementResponse saveRequirement(CurrentUser user, Long sessionId, Transcript transcript, Extraction extraction) {
        owned(user,sessionId,true);
        Long latest = messages.findFirstBySessionIdOrderByIdDesc(sessionId).map(ChatMessage::getId).orElse(null);
        if (!Objects.equals(latest,transcript.lastMessageId())) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        var old = requirements.forSession(user,sessionId);
        if (old.isPresent()) {
            if (!old.get().testTypeCode().equals(extraction.testTypeCode())) throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "기존 시험조건과 시험유형이 다릅니다. 새 상담을 시작해 주세요.",null);
            var result = requirements.mergeExtracted(user,old.get().id(),extraction.conditions());
            sessions.recordActivity(sessionId, OffsetDateTime.now());
            return result;
        }
        var result = requirements.createForSession(user,transcript.ownerId(),sessionId,
                new RequirementInput(extraction.testTypeCode(),"AI 상담 시험조건",null,null,null,extraction.conditions()));
        sessions.recordActivity(sessionId, OffsetDateTime.now());
        return result;
    }
    public Long ownerId(CurrentUser user,Long id) { return owned(user,id,false).getUserId(); }
    private ChatSession owned(CurrentUser user,Long id,boolean lock) {
        AccessGuard.requireLogin(user);
        ChatSession s = (lock ? sessions.lockById(id) : sessions.findById(id))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        AccessGuard.requireOwner(user,s.getUserId());
        return s;
    }
    private Pageable page(Pageable p,String key,Sort.Direction direction) {
        return PageRequest.of(p.getPageNumber(),Math.min(p.getPageSize(),100),Sort.by(direction,key));
    }
}
