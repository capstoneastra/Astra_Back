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
    final kr.astra.equipment.service.EquipmentQueryService equipment=mock(kr.astra.equipment.service.EquipmentQueryService.class);
    final CurrentUser user=new CurrentUser(1L,UserRole.COMPANY_USER,7L);
    final ChatService service=new ChatService(persistence,extraction,llm,new PromptLoader(),new StructuredJson(JsonMapper.builder().build()),equipment);
    ChatPersistence.Turn turn;
    @BeforeEach void setup() {
        var message=new ChatResponse.Message(11L,ChatRole.USER,"질량 15kg",Map.of(),null);
        turn=new ChatPersistence.Turn(5L,message,new ChatPersistence.Transcript(1L,11L,List.of(message),"질량 15kg"));
        when(persistence.begin(user,5L,"질량 15kg")).thenReturn(turn);
        when(extraction.extract(anyString(),isNull())).thenReturn(new ConditionExtractionService.Extraction("VIBRATION",List.of(),Map.of()));
    }
    @Test void consultationGetsUpToFiveDbEquipmentOfThatTestTypeExcludingOwnOrganization() {
        // 상담 답변에 우리 DB 장비를 소개할 수 있게 후보를 같이 보냄 (적합 판정은 매칭이 함)
        var A=kr.astra.equipment.entity.Availability.AVAILABLE; var B=kr.astra.equipment.entity.Availability.BUSY;
        var EXT=kr.astra.equipment.entity.AccessScope.EXTERNAL_ALLOWED;
        List<kr.astra.equipment.dto.EquipmentCandidate> all=new ArrayList<>();
        all.add(new kr.astra.equipment.dto.EquipmentCandidate(99L,"E99","내 조직 장비",7L,"우리 회사","서울",true,A,false,EXT));
        for (long id=1;id<=7;id++) all.add(new kr.astra.equipment.dto.EquipmentCandidate(id,"E"+id,"진동시험기"+id,2L,"미리내센터","대전",true,
                id%2==0 ? A : B,false,EXT));
        when(equipment.findCandidates("VIBRATION",null)).thenReturn(all);
        when(llm.complete(any())).thenReturn(new LlmClient.Reply("이런 장비가 있습니다.","model","request",1,2));
        service.send(user,5L,"질량 15kg",null);
        var request=org.mockito.ArgumentCaptor.forClass(LlmClient.Request.class);
        verify(llm).complete(request.capture());
        String payload=request.getValue().input();
        assertThat(payload).contains("equipmentCandidates","진동시험기2","미리내센터","AVAILABLE","EXTERNAL_ALLOWED")
                .doesNotContain("내 조직 장비");
        assertThat(request.getValue().system()).contains("equipmentCandidates");
        // 예약 가능(AVAILABLE)부터, 최대 5대. 화면이 카드로 보여 줄 수 있게 id 를 메타데이터에
        verify(persistence).complete(eq(user),eq(turn),any(),argThat(m ->
                m.get("suggestedEquipmentIds") instanceof List<?> ids && ids.size()==5 && ids.subList(0,3).equals(List.of(2L,4L,6L))));
    }
    @Test void equipmentLookupFailureStillAnswers() {
        when(equipment.findCandidates(anyString(),any())).thenThrow(new RuntimeException("db down"));
        when(llm.complete(any())).thenReturn(new LlmClient.Reply("답변","model","request",1,2));
        service.send(user,5L,"질량 15kg",null);
        verify(persistence).complete(eq(user),eq(turn),eq("답변"),any());
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
    @Test void metadataKeepsOnlySmallAuditFieldsNotPromptOrTranscript() {
        // 매 턴 전체 대화가 promptInput 으로 다시 저장되면 메타데이터가 제곱으로 커짐
        when(extraction.extract(anyString(),isNull())).thenReturn(new ConditionExtractionService.Extraction("VIBRATION",List.of(),
                Map.of("model","m","requestId","x","inputTokens",1L,"outputTokens",1L)));
        when(llm.complete(any())).thenReturn(new LlmClient.Reply("답변","model","request",1,2));
        service.send(user,5L,"질량 15kg",null);
        verify(persistence).complete(eq(user),eq(turn),eq("답변"),argThat(m -> {
            @SuppressWarnings("unchecked") var conversation=(Map<String,Object>)m.get("conversation");
            return !m.containsKey("prompt") && !m.containsKey("promptInput") && !m.containsKey("rawResponse")
                    && conversation.keySet().containsAll(List.of("model","requestId","inputTokens","outputTokens"))
                    && !conversation.containsKey("prompt") && !conversation.containsKey("promptInput") && !conversation.containsKey("rawResponse")
                    && "VIBRATION".equals(m.get("suggestedTestType"));
        }));
    }
    @Test void extractionUpstreamFailureStillAnswersWithPreviousConditions() {
        // 조건 추출이 실패해도 상담 답변은 이어감 (이전 턴의 추출 조건을 그대로 넘김)
        var previous=new ChatResponse.Message(10L,ChatRole.ASSISTANT,"질량을 알려 주세요",
                Map.of("extractedConditions",List.of(Map.of("key","sample_mass")),"suggestedTestType","VIBRATION"),null);
        var message=turn.userMessage();
        turn=new ChatPersistence.Turn(5L,message,new ChatPersistence.Transcript(1L,11L,List.of(previous,message),"질량 15kg"));
        when(persistence.begin(user,5L,"질량 15kg")).thenReturn(turn);
        when(extraction.extract(anyString(),any())).thenThrow(new BusinessException(ErrorCode.AI_UPSTREAM_ERROR));
        when(llm.complete(any())).thenReturn(new LlmClient.Reply("답변","model","request",1,2));
        service.send(user,5L,"질량 15kg",null);
        var request=org.mockito.ArgumentCaptor.forClass(LlmClient.Request.class);
        verify(llm).complete(request.capture());
        assertThat(request.getValue().input()).contains("sample_mass");
        verify(persistence).complete(eq(user),eq(turn),eq("답변"),argThat(m ->
                m.get("extractedConditions").equals(List.of(Map.of("key","sample_mass")))));
        verify(persistence,never()).fail(any(),any());
    }
    @Test void knownTestTypeIsPassedSoDetectionCallIsSkipped() {
        var message=turn.userMessage();
        turn=new ChatPersistence.Turn(5L,message,new ChatPersistence.Transcript(1L,11L,List.of(message),"질량 15kg","VIBRATION"));
        when(persistence.begin(user,5L,"질량 15kg")).thenReturn(turn);
        when(extraction.extract("질량 15kg","VIBRATION")).thenReturn(new ConditionExtractionService.Extraction("VIBRATION",List.of(),Map.of()));
        when(llm.complete(any())).thenReturn(new LlmClient.Reply("답변","model","request",1,2));
        service.send(user,5L,"질량 15kg",null);
        verify(extraction).extract("질량 15kg","VIBRATION");
        var transcript=turn.transcript();
        when(persistence.transcript(user,5L)).thenReturn(transcript);
        service.requirement(user,5L);
        verify(extraction,times(2)).extract("질량 15kg","VIBRATION");
    }
    @Test void deniedSessionNeverCallsLlm() {
        when(persistence.begin(user,5L,"질량 15kg")).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.send(user,5L,"질량 15kg",null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(llm,extraction);
    }
}
