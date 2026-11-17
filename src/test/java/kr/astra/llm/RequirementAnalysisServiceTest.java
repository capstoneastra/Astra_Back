package kr.astra.llm;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import kr.astra.global.exception.*;
import kr.astra.global.security.CurrentUser;
import kr.astra.llm.client.LlmClient;
import kr.astra.llm.prompt.PromptLoader;
import kr.astra.llm.service.*;
import kr.astra.requirement.dto.AnalysisResponse;
import kr.astra.requirement.service.RequirementApplicationService;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

class RequirementAnalysisServiceTest {
    final CurrentUser user=new CurrentUser(1L,UserRole.COMPANY_USER,null);
    final RequirementApplicationService requirements=mock(RequirementApplicationService.class);
    final LlmClient llm=mock(LlmClient.class);
    final RequirementAnalysisService service=new RequirementAnalysisService(requirements,llm,
            new StructuredJson(JsonMapper.builder().build()),new PromptLoader());
    final AnalysisResponse analysis=new AnalysisResponse(7L,"VIBRATION",List.of(),List.of(
            new AnalysisResponse.Missing("sample_mass","시료 질량","시료 질량을(를) 알려 주세요."),
            new AnalysisResponse.Missing("fixture_mass","고정구 질량","고정구 질량을(를) 알려 주세요.")),List.of(),false);
    @BeforeEach void setup() { when(requirements.analyze(user,7L)).thenReturn(analysis); }

    @Test void llmQuestionsReplaceDefaults() {
        when(llm.complete(any())).thenReturn(new LlmClient.Reply("{\"questions\":[{\"key\":\"sample_mass\",\"question\":\"시료는 몇 kg인가요?\"},"
                + "{\"key\":\"fixture_mass\",\"question\":\"고정구는 몇 kg인가요?\"}]}","m","r",1,1));
        assertThat(service.analyze(user,7L).missing()).extracting(AnalysisResponse.Missing::question)
                .containsExactly("시료는 몇 kg인가요?","고정구는 몇 kg인가요?");
    }
    @Test void upstreamFailureFallsBackToDeterministicQuestions() {
        // 질문 문구 다듬기는 부가 기능: AI 가 실패해도 분석 자체는 502 가 아니라 기본 질문으로 응답
        for (var failure:List.of(new BusinessException(ErrorCode.AI_UPSTREAM_ERROR),new BusinessException(ErrorCode.TOO_MANY_REQUESTS))) {
            reset(llm); when(llm.complete(any())).thenThrow(failure);
            assertThat(service.analyze(user,7L)).isEqualTo(analysis);
        }
    }
    @Test void partialOrMalformedOutputKeepsDefaultsForMissingQuestions() {
        when(llm.complete(any())).thenReturn(new LlmClient.Reply("{\"questions\":[{\"key\":\"sample_mass\",\"question\":\"시료는 몇 kg인가요?\"}]}","m","r",1,1));
        assertThat(service.analyze(user,7L).missing()).extracting(AnalysisResponse.Missing::question)
                .containsExactly("시료는 몇 kg인가요?","고정구 질량을(를) 알려 주세요.");
        when(llm.complete(any())).thenReturn(new LlmClient.Reply("not json","m","r",1,1));
        assertThat(service.analyze(user,7L)).isEqualTo(analysis);
    }
    @Test void nonAiErrorsStillPropagate() {
        when(requirements.analyze(user,7L)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.analyze(user,7L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(llm);
    }
}
