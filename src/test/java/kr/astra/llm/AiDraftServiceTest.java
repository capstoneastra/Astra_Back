package kr.astra.llm;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import kr.astra.equipment.dto.EquipmentDetailResponse;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.global.exception.*;
import kr.astra.global.security.CurrentUser;
import kr.astra.llm.client.LlmClient;
import kr.astra.llm.dto.AiDraftRequest;
import kr.astra.llm.prompt.PromptLoader;
import kr.astra.llm.service.*;
import kr.astra.matching.dto.MatchResponse;
import kr.astra.matching.service.MatchingService;
import kr.astra.request.dto.CreateDraftCommand;
import kr.astra.request.service.TestRequestService;
import kr.astra.requirement.dto.RequirementSnapshot;
import kr.astra.requirement.entity.ConditionStatus;
import kr.astra.requirement.service.RequirementApplicationService;
import kr.astra.user.dto.UserMeResponse;
import kr.astra.user.entity.*;
import kr.astra.user.service.UserService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

class AiDraftServiceTest {
    final CurrentUser user=new CurrentUser(1L,UserRole.COMPANY_USER,null);
    final RequirementApplicationService requirements=mock(RequirementApplicationService.class);
    final MatchingService matching=mock(MatchingService.class);
    final EquipmentService equipment=mock(EquipmentService.class);
    final UserService users=mock(UserService.class);
    final TestRequestService requests=mock(TestRequestService.class);
    final LlmClient llm=mock(LlmClient.class);
    @SuppressWarnings("unchecked") final ObjectProvider<TestRequestService> provider=mock(ObjectProvider.class);
    final AiDraftService drafts=new AiDraftService(requirements,matching,equipment,users,provider,llm,new PromptLoader(),
            new StructuredJson(JsonMapper.builder().build()));
    final AiDraftRequest input=new AiDraftRequest(7L,48L,31L);
    @BeforeEach void setup() {
        when(requirements.snapshot(user,7L)).thenReturn(new RequirementSnapshot(7L,"VIBRATION","시험","명시한 목적","시료",1,
                List.of(new RequirementSnapshot.Condition("mass","질량",BigDecimal.TEN,null,null,null,"kg",ConditionStatus.CONFIRMED))));
        when(requirements.ownerId(user,7L)).thenReturn(1L);
        when(requirements.lockSnapshot(user,7L)).thenAnswer(i -> requirements.snapshot(user,7L));
        var detail=mock(EquipmentDetailResponse.class);
        when(detail.id()).thenReturn(48L); when(detail.externalId()).thenReturn("ASTRA-48"); when(detail.name()).thenReturn("시험기");
        when(detail.testTypes()).thenReturn(List.of("VIBRATION"));
        when(equipment.getDetail(48L)).thenReturn(detail);
        when(users.getMe(1L)).thenReturn(new UserMeResponse(1L,"test@example.com","시험자",null,UserRole.COMPANY_USER,UserStatus.ACTIVE,null,null,null));
        when(matching.validateSelection(user,7L,48L,31L)).thenReturn(mock(MatchResponse.Result.class));
        when(provider.getIfAvailable()).thenReturn(requests);
        when(llm.complete(any())).thenReturn(new LlmClient.Reply("{\"notes\":\"기관 확인 필요\",\"aiNotes\":[\"사용자 확인 필요\"]}","test-model","test-id",1,1));
    }
    @Test void generatesContentFromFactsAndCallsOnlyExistingCreateDraftContract() {
        var prepared=drafts.prepare(user,input);
        when(requests.createDraft(any())).thenReturn(15L);
        assertThat(drafts.create(user,input,prepared)).isEqualTo(15L);
        var command=ArgumentCaptor.forClass(CreateDraftCommand.class);
        verify(requests).createDraft(command.capture());
        assertThat(command.getValue().aiGenerated()).isTrue();
        assertThat(command.getValue().requesterId()).isEqualTo(1L);
        assertThat(command.getValue().matchResultId()).isEqualTo(31L);
        assertThat(command.getValue().content()).containsKeys("applicant","test","sample","conditions","equipment","schedule","notes");
        assertThat((Map<?,?>)command.getValue().content().get("schedule")).isEmpty();
        assertThat(((Map<?,?>)command.getValue().content().get("test")).containsKey("standard")).isFalse();
        assertThat(((Map<?,?>)command.getValue().content().get("sample")).containsKey("readyDate")).isFalse();
        verifyNoMoreInteractions(requests);
    }
    @Test void mismatchedLinkedResultOrOwnerIsRejectedBeforeLlmAndWrite() {
        when(matching.validateSelection(user,7L,48L,31L)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> drafts.prepare(user,input)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(llm,requests);
    }
    @Test void finalApiDoesNotInventReceiptOrWriteUnreturnableDraft() {
        assertThatThrownBy(() -> drafts.createForApi(user,input)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessage()).contains("BE-B"));
        verifyNoInteractions(llm,requests);
    }
    @Test void missingImplementationIsExplicitAndDoesNotFakeSuccess() {
        var prepared=drafts.prepare(user,input);
        when(provider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> drafts.create(user,input,prepared)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(requests);
    }
}
