package kr.astra.chat;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.function.Consumer;
import kr.astra.chat.dto.ChatResponse;
import kr.astra.chat.entity.ChatRole;
import kr.astra.chat.service.*;
import kr.astra.global.exception.*;
import kr.astra.global.security.CurrentUser;
import kr.astra.llm.client.LlmClient;
import kr.astra.llm.prompt.PromptLoader;
import kr.astra.llm.service.*;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
class ChatServiceTest {
    final ChatPersistence persistence=mock(ChatPersistence.class);
    final ConditionExtractionService extraction=mock(ConditionExtractionService.class);
    final LlmClient llm=mock(LlmClient.class);
    final CurrentUser user=new CurrentUser(1L,UserRole.COMPANY_USER,null);
    final ChatService service=new ChatService(persistence,extraction,llm,new PromptLoader(),new StructuredJson(JsonMapper.builder().build()));
    ChatPersistence.Turn turn;
    @BeforeEach void setup() {
        var message=new ChatResponse.Message(11L,ChatRole.USER,"질량 15kg",Map.of(),null);
        turn=new ChatPersistence.Turn(5L,message,new ChatPersistence.Transcript(1L,11L,List.of(message),"질량 15kg"));
        when(persistence.begin(user,5L,"질량 15kg")).thenReturn(turn);
        when(extraction.extract(anyString(),isNull())).thenReturn(new ConditionExtractionService.Extraction("VIBRATION",List.of(),Map.of()));
    }
    @Test void extractionOrChatFailureMarksUserMessageFailedAndNeverPersistsAssistant() {
        when(llm.complete(any())).thenThrow(new BusinessException(ErrorCode.AI_UPSTREAM_ERROR));
        assertThatThrownBy(() -> service.send(user,5L,"질량 15kg",null)).isInstanceOf(BusinessException.class);
        verify(persistence).fail(user,turn);
        verify(persistence,never()).complete(any(),any(),any(),any());
    }
    @Test void interruptedStreamNeverPersistsPartialAssistant() {
        doAnswer(i -> {
            Consumer<String> callback=i.getArgument(1);
            callback.accept("partial");
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }).when(llm).stream(any(),any());
        List<String> tokens=new ArrayList<>();
        assertThatThrownBy(() -> service.send(user,5L,"질량 15kg",tokens::add)).isInstanceOf(BusinessException.class);
        assertThat(tokens).containsExactly("partial");
        verify(persistence).fail(user,turn);
        verify(persistence,never()).complete(any(),any(),any(),any());
    }
    @Test void successSavesOnlyCompletedResponseAndMetadata() {
        when(llm.complete(any())).thenReturn(new LlmClient.Reply("고정구 질량을 알려 주세요.","model","request",1,2));
        service.send(user,5L,"질량 15kg",null);
        verify(persistence).complete(eq(user),eq(turn),eq("고정구 질량을 알려 주세요."),argThat(m ->
                m.containsKey("conversation") && m.containsKey("extractedConditions")));
        verify(persistence,never()).fail(any(),any());
    }
    @Test void deniedSessionNeverCallsLlm() {
        when(persistence.begin(user,5L,"질량 15kg")).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.send(user,5L,"질량 15kg",null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(llm,extraction);
    }
}
