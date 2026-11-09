package kr.astra.llm;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import kr.astra.global.exception.BusinessException;
import kr.astra.llm.client.LlmClient;
import kr.astra.llm.prompt.PromptLoader;
import kr.astra.llm.service.*;
import kr.astra.matching.dto.MatchResponse;
import kr.astra.matching.entity.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
class MatchExplanationTest {
    final LlmClient llm=mock(LlmClient.class);
    final MatchExplanationService service=new MatchExplanationService(llm,new PromptLoader(),new StructuredJson(JsonMapper.builder().build()));
    final List<MatchExplanationService.Input> inputs=List.of(new MatchExplanationService.Input(48L,MatchVerdict.UNSUITABLE,
            List.of(new MatchResponse.Check("mass","20 kg","10 kg",CheckResult.NOT_MET,"MAX_LIMIT"))));
    void reply(String verdict,String met,String notMet) {
        when(llm.complete(any())).thenReturn(new LlmClient.Reply("{\"results\":[{\"equipmentId\":48,\"verdict\":\"" + verdict
                + "\",\"metKeys\":" + met + ",\"notMetKeys\":" + notMet
                + ",\"unknownKeys\":[],\"explanation\":\"질량 조건이 장비 한도를 초과합니다.\"}]}","test","test",1,1));
    }
    @Test void explanationCannotChangeVerdictOrMoveFailedConditionToMet() {
        reply("SUITABLE","[\"mass\"]","[]");
        assertThatThrownBy(() -> service.explain(inputs)).isInstanceOf(BusinessException.class);
        reply("UNSUITABLE","[\"mass\"]","[]");
        assertThatThrownBy(() -> service.explain(inputs)).isInstanceOf(BusinessException.class);
        reply("UNSUITABLE","[]","[\"mass\"]");
        assertThat(service.explain(inputs)).containsKey(48L);
    }
}
