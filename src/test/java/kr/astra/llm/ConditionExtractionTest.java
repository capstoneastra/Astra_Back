package kr.astra.llm;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import kr.astra.global.exception.*;
import kr.astra.llm.client.*;
import kr.astra.llm.prompt.PromptLoader;
import kr.astra.llm.service.*;
import kr.astra.requirement.entity.*;
import kr.astra.requirement.service.ConditionValidator;
import kr.astra.testtype.dto.ConditionRuleResponse;
import kr.astra.testtype.service.TestTypeService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ConditionExtractionTest {
    final LlmClient llm=mock(LlmClient.class);
    final TestTypeService types=mock(TestTypeService.class);
    final StructuredJson json=new StructuredJson(JsonMapper.builder().build());
    final ConditionExtractionService service=new ConditionExtractionService(llm,new PromptLoader(),json,types,new ConditionValidator());
    @BeforeEach void setup() {
        when(types.specDefinitions()).thenReturn(List.of(
                new kr.astra.equipment.service.SpecDefinitionService.Definition("payload", "질량", "kg", ConditionValueType.NUMBER),
                new kr.astra.equipment.service.SpecDefinitionService.Definition("fixture", "고정구", "kg", ConditionValueType.NUMBER)));
        when(types.getConditionRules("VIBRATION")).thenReturn(List.of(
                new ConditionRuleResponse("mass","질량",ConditionValueType.NUMBER,"kg",true,"payload",ConditionComparator.MAX_LIMIT),
                new ConditionRuleResponse("fixture","고정구",ConditionValueType.NUMBER,"kg",true,"fixture",ConditionComparator.MAX_LIMIT)));
    }
    String output(String key,String value,String unit,String evidence,String confidence) {
        return "{\"conditions\":[{\"key\":\"" + key + "\",\"value\":" + value + ",\"unit\":\"" + unit
                + "\",\"status\":\"CONFIRMED\",\"confidence\":" + confidence + ",\"evidence\":\"" + evidence + "\"}]}";
    }
    void reply(String text) { when(llm.complete(any())).thenReturn(new LlmClient.Reply(text,"test-model","test-request",10,10)); }
    @Test void validatesEvidenceAndConvertsBeforeReturningAndAddsMissing() {
        reply(output("mass","15000","g","질량 15000g","0.95"));
        var result=service.extract("질량 15000g","VIBRATION");
        assertThat(result.conditions().getFirst().value()).isEqualByComparingTo("15");
        assertThat(result.conditions().getFirst().unit()).isEqualTo("kg");
        assertThat(result.conditions().getLast().status()).isEqualTo(ConditionStatus.MISSING);
        assertThat(result.metadata()).containsKeys("prompt","rawResponse","model");
    }
    @Test void uncertainValueRequiresUserConfirmation() {
        reply(output("mass","15","kg","질량 15kg","0.5"));
        assertThat(service.extract("질량 15kg","VIBRATION").conditions().getFirst().status()).isEqualTo(ConditionStatus.UNCONFIRMED);
    }
    @ParameterizedTest @ValueSource(strings={"not json","{}","{\"conditions\":{}}","{\"conditions\":[]} trailing","{\"conditions\":[],\"unauthorized\":true}"})
    void malformedJsonIsUpstreamError(String raw) {
        reply(raw);
        assertThatThrownBy(() -> service.extract("질량 15kg","VIBRATION")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_UPSTREAM_ERROR));
    }
    @Test void rejectsUnknownKeysFabricatedNumbersUnitsAndConfidence() {
        for (String raw:List.of(output("invented","15","kg","질량 15kg","0.95"),
                output("mass","99","kg","질량 15kg","0.95"),
                output("mass","15","GHz","질량 15kg","0.95"),
                output("mass","15","kg","없는 근거 15kg","0.95"),
                output("mass","15","kg","질량 15kg","2"),
                output("mass","\"15\"","kg","질량 15kg","0.9"))) {
            reply(raw);
            assertThatThrownBy(() -> service.extract("질량 15kg","VIBRATION")).isInstanceOf(BusinessException.class);
        }
    }
    @Test void providerFailureIsNotReplacedBySuccessfulExtraction() {
        when(llm.complete(any())).thenThrow(new BusinessException(ErrorCode.AI_UPSTREAM_ERROR));
        assertThatThrownBy(() -> service.extract("질량 15kg","VIBRATION")).isInstanceOf(BusinessException.class);
    }
    @Test void missingKeyNeverCreatesFakeProviderResponse() {
        var client=new AnthropicLlmClient("","configured-model");
        var request=new LlmClient.Request("prompt","input",null,"low");
        assertThatThrownBy(() -> client.complete(request)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_UPSTREAM_ERROR));
        assertThatThrownBy(() -> client.stream(request,t -> fail("No token may be produced"))).isInstanceOf(BusinessException.class);
    }
}
