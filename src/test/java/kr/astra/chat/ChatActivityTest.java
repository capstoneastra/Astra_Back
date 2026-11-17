package kr.astra.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.astra.chat.dto.ChatResponse;
import kr.astra.chat.entity.ChatMessage;
import kr.astra.chat.entity.ChatRole;
import kr.astra.chat.entity.ChatSession;
import kr.astra.chat.repository.ChatMessageRepository;
import kr.astra.chat.repository.ChatSessionRepository;
import kr.astra.chat.service.ChatPersistence;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.security.CurrentUser;
import kr.astra.requirement.service.RequirementApplicationService;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatActivityTest {
    private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final RequirementApplicationService requirements = mock(RequirementApplicationService.class);
    private final ChatPersistence persistence = new ChatPersistence(sessions, messages, requirements);
    private final CurrentUser user = new CurrentUser(7L, UserRole.COMPANY_USER, null);
    private final ChatSession session = new ChatSession(7L, "진동시험");
    private ChatMessage original;
    private ChatPersistence.Turn turn;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(session, "id", 3L);
        when(sessions.lockById(3L)).thenReturn(Optional.of(session));
        original = new ChatMessage(3L, ChatRole.USER, "진동시험", Map.of("state", "PENDING"));
        ReflectionTestUtils.setField(original, "id", 11L);
        ReflectionTestUtils.setField(original, "createdAt", OffsetDateTime.now());
        turn = new ChatPersistence.Turn(3L, ChatResponse.Message.of(original),
                new ChatPersistence.Transcript(7L, 11L, List.of(ChatResponse.Message.of(original)), "진동시험"));
    }

    @Test void acceptedUserMessageUpdatesSessionActivityAfterPersistence() {
        when(sessions.findById(3L)).thenReturn(Optional.of(session));
        when(messages.saveAndFlush(any())).thenReturn(original);
        when(messages.findTop101BySessionIdOrderByIdDesc(3L)).thenReturn(List.of(original));
        persistence.begin(user, 3L, "진동시험");
        var order = inOrder(sessions, messages);
        order.verify(sessions).lockById(3L);
        order.verify(messages).saveAndFlush(any());
        order.verify(sessions).recordActivity(eq(3L), any(OffsetDateTime.class));
    }

    @Test void completedAssistantMessageUpdatesActivityButFailureDoesNot() {
        when(messages.findById(11L)).thenReturn(Optional.of(original));
        when(messages.findFirstBySessionIdOrderByIdDesc(3L)).thenReturn(Optional.of(original));
        when(messages.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        persistence.complete(user, turn, "조건을 알려 주세요", Map.of());
        verify(sessions).recordActivity(eq(3L), any(OffsetDateTime.class));
        assertThat(original.getMetadata()).containsEntry("state", "DONE");
        clearInvocations(sessions);
        persistence.fail(user, turn);
        verify(sessions, never()).recordActivity(any(), any());
    }

    @Test void rejectedConcurrentMessageNeverUpdatesActivityOrSavesMessage() {
        when(messages.findFirstBySessionIdOrderByIdDesc(3L)).thenReturn(Optional.of(original));
        assertThatThrownBy(() -> persistence.begin(user, 3L, "중복 전송")).isInstanceOf(BusinessException.class);
        verify(sessions, never()).recordActivity(any(), any());
        verify(messages, never()).saveAndFlush(any());
    }

    @Test void transcriptExcludesFailedUserMessagesSoRetriesAreNotDuplicated() {
        // 응답을 못 받은(FAILED) 질문을 다시 보내면 같은 문장이 두 번 추출·상담 입력에 들어가던 문제
        when(sessions.findById(3L)).thenReturn(Optional.of(session));
        var failed = new ChatMessage(3L, ChatRole.USER, "시료 15kg", Map.of("state", "FAILED"));
        ReflectionTestUtils.setField(failed, "id", 9L);
        var retried = new ChatMessage(3L, ChatRole.USER, "시료 15kg", Map.of("state", "PENDING"));
        ReflectionTestUtils.setField(retried, "id", 10L);
        when(messages.findTop101BySessionIdOrderByIdDesc(3L)).thenReturn(List.of(retried, failed));
        var transcript = persistence.transcript(user, 3L);
        assertThat(transcript.messages()).extracting(ChatResponse.Message::id).containsExactly(10L);
        assertThat(transcript.userText()).isEqualTo("시료 15kg");
        assertThat(transcript.lastMessageId()).isEqualTo(10L);
    }

    @Test void knownTestTypePrefersExistingRequirementThenLastSuggestedType() {
        // 시험유형을 이미 알면 매 턴 유형 판별 LLM 호출을 건너뛰고, 기존 시험조건과 다른 유형이 나와 저장이 막히지 않게 함
        when(sessions.findById(3L)).thenReturn(Optional.of(session));
        var assistant = new ChatMessage(3L, ChatRole.ASSISTANT, "질량을 알려 주세요", Map.of("suggestedTestType", "VIBRATION"));
        ReflectionTestUtils.setField(assistant, "id", 10L);
        when(messages.findTop101BySessionIdOrderByIdDesc(3L)).thenReturn(List.of(original, assistant));
        assertThat(persistence.transcript(user, 3L).knownTestType()).isEqualTo("VIBRATION");
        var requirement = mock(kr.astra.requirement.dto.RequirementResponse.class);
        when(requirement.testTypeCode()).thenReturn("TENSILE");
        when(requirements.forSession(user, 3L)).thenReturn(Optional.of(requirement));
        assertThat(persistence.transcript(user, 3L).knownTestType()).isEqualTo("TENSILE");
    }

    @Test void deniedOwnerNeverUpdatesActivityOrReadsMessages() {
        var stranger = new CurrentUser(8L, UserRole.COMPANY_USER, null);
        assertThatThrownBy(() -> persistence.begin(stranger, 3L, "권한 없음")).isInstanceOf(BusinessException.class);
        verify(sessions, never()).recordActivity(any(), any());
        verifyNoInteractions(messages);
    }
}
