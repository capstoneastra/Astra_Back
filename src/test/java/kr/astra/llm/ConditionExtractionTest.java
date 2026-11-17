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
                new kr.astra.equipment.service.SpecDefinitionService.Definition("fixture", "고정구", "kg", ConditionValueType.NUMBER),
                new kr.astra.equipment.service.SpecDefinitionService.Definition("temperature", "온도", "°C", ConditionValueType.RANGE),
                new kr.astra.equipment.service.SpecDefinitionService.Definition("humidity", "습도", "%RH", ConditionValueType.RANGE),
                new kr.astra.equipment.service.SpecDefinitionService.Definition("magnification", "배율", "x", ConditionValueType.NUMBER),
                new kr.astra.equipment.service.SpecDefinitionService.Definition("frequency", "주파수", "Hz", ConditionValueType.RANGE)));
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
        // 대화마다 쌓이는 chat 메타데이터가 커지지 않게 프롬프트·원문·원응답은 저장하지 않음 (모델·요청 ID·토큰·프롬프트 지문만)
        assertThat(result.metadata()).containsKeys("model","requestId","inputTokens","outputTokens","promptHash")
                .doesNotContainKeys("prompt","promptInput","rawResponse");
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
    @Test void dropsUnknownKeysFabricatedNumbersUnitsAndConfidencePerItem() {
        // 항목 하나가 틀렸다고 대화 전체가 502 가 되지 않게: 잘못된 항목만 버리고 나머지는 MISSING 으로 남김
        for (String raw:List.of(output("invented","15","kg","질량 15kg","0.95"),
                output("mass","99","kg","질량 15kg","0.95"),
                output("mass","15","GHz","질량 15kg","0.95"),
                output("mass","15","kg","질량 15kg","2"),
                output("mass","\"15\"","kg","질량 15kg","0.9"))) {
            reply(raw);
            assertThat(service.extract("질량 15kg","VIBRATION").conditions())
                    .allSatisfy(c -> assertThat(c.status()).isEqualTo(ConditionStatus.MISSING));
        }
    }
    @Test void invalidItemIsDroppedWhileValidItemsAreKept() {
        reply("{\"conditions\":[{\"key\":\"mass\",\"value\":15,\"unit\":\"kg\",\"status\":\"CONFIRMED\",\"confidence\":0.95,\"evidence\":\"질량 15kg\"},"
                + "{\"key\":\"fixture\",\"value\":99,\"unit\":\"kg\",\"status\":\"CONFIRMED\",\"confidence\":0.95,\"evidence\":\"고정구 3kg\"}]}");
        var conditions=service.extract("질량 15kg, 고정구 3kg","VIBRATION").conditions();
        assertThat(conditions).filteredOn(c -> c.key().equals("mass")).singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo(ConditionStatus.CONFIRMED));
        assertThat(conditions).filteredOn(c -> c.key().equals("fixture")).singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo(ConditionStatus.MISSING));
    }
    @Test void nonVerbatimEvidenceIsDowngradedToUnconfirmedWithNote() {
        reply(output("mass","15","kg","질량 15kg","0.95"));
        var mass=service.extract("시료 질량은 15 kg 입니다","VIBRATION").conditions().getFirst();
        assertThat(mass.key()).isEqualTo("mass");
        assertThat(mass.value()).isEqualByComparingTo("15");
        assertThat(mass.status()).isEqualTo(ConditionStatus.UNCONFIRMED);
        assertThat(mass.note()).isNotBlank();
    }
    @Test void thousandsSeparatorInSourceNumberIsAccepted() {
        reply(output("mass","2400","kg","질량 2,400kg","0.95"));
        var mass=service.extract("질량 2,400kg","VIBRATION").conditions().getFirst();
        assertThat(mass.value()).isEqualByComparingTo("2400");
        assertThat(mass.status()).isEqualTo(ConditionStatus.CONFIRMED);
    }
    @Test void celsiusAndPercentAliasesAreNormalizedToRuleUnits() {
        when(types.getConditionRules("TEMPERATURE_HUMIDITY")).thenReturn(List.of(
                new ConditionRuleResponse("temperature","온도",ConditionValueType.RANGE,"°C",true,"temperature",ConditionComparator.RANGE_WITHIN),
                new ConditionRuleResponse("humidity","습도",ConditionValueType.RANGE,"%RH",false,"humidity",ConditionComparator.RANGE_WITHIN)));
        reply("{\"conditions\":[{\"key\":\"temperature\",\"min\":-40,\"max\":85,\"unit\":\"℃\",\"status\":\"CONFIRMED\",\"confidence\":0.95,\"evidence\":\"-40℃ ~ 85℃\"},"
                + "{\"key\":\"humidity\",\"value\":95,\"unit\":\"%\",\"status\":\"CONFIRMED\",\"confidence\":0.95,\"evidence\":\"습도 95%\"}]}");
        var conditions=service.extract("온도 -40℃ ~ 85℃, 습도 95%","TEMPERATURE_HUMIDITY").conditions();
        assertThat(conditions).extracting(c -> c.key()+":"+c.unit()+":"+c.status())
                .containsExactly("temperature:°C:CONFIRMED","humidity:%RH:CONFIRMED");
    }
    @Test void koreanDegreeWordWithBelowZeroIsAccepted() {
        when(types.getConditionRules("TEMPERATURE_HUMIDITY")).thenReturn(List.of(
                new ConditionRuleResponse("temperature","온도",ConditionValueType.RANGE,"°C",true,"temperature",ConditionComparator.RANGE_WITHIN)));
        reply("{\"conditions\":[{\"key\":\"temperature\",\"min\":-40,\"max\":85,\"unit\":\"도\",\"status\":\"CONFIRMED\",\"confidence\":0.95,\"evidence\":\"영하 40도에서 85도\"}]}");
        var temperature=service.extract("영하 40도에서 85도까지 시험","TEMPERATURE_HUMIDITY").conditions().getFirst();
        assertThat(temperature.min()).isEqualByComparingTo("-40");
        assertThat(temperature.unit()).isEqualTo("°C");
        assertThat(temperature.status()).isEqualTo(ConditionStatus.CONFIRMED);
    }
    @Test void magnificationAliasesAreNormalized() {
        when(types.getConditionRules("MICROSCOPY")).thenReturn(List.of(
                new ConditionRuleResponse("magnification","배율",ConditionValueType.NUMBER,"x",true,"magnification",ConditionComparator.MAX_LIMIT)));
        for (String unit:List.of("배","X","×")) {
            reply(output("magnification","1000",unit,"1000"+unit,"0.95"));
            var c=service.extract("배율 1000"+unit+" 필요","MICROSCOPY").conditions().getFirst();
            assertThat(c.unit()).isEqualTo("x");
            assertThat(c.status()).isEqualTo(ConditionStatus.CONFIRMED);
        }
    }
    @Test void convertedValueIsAcceptedWhenOriginalValueAndUnitAreInText() {
        // 규칙 단위가 Hz 로 바뀐 뒤(V6) 모델이 2.4GHz 를 2400000000Hz 로 환산해 돌려줘도 원문 근거로 인정
        when(types.getConditionRules("ELECTRICAL_RF_ANALYSIS")).thenReturn(List.of(
                new ConditionRuleResponse("frequency","주파수",ConditionValueType.RANGE,"Hz",true,"frequency",ConditionComparator.RANGE_WITHIN)));
        reply("{\"conditions\":[{\"key\":\"frequency\",\"min\":1000000000,\"max\":2400000000,\"unit\":\"Hz\",\"status\":\"CONFIRMED\",\"confidence\":0.95,\"evidence\":\"1기가헤르츠~2.4기가헤르츠\"}]}");
        var c=service.extract("주파수 1기가헤르츠~2.4기가헤르츠 측정","ELECTRICAL_RF_ANALYSIS").conditions().getFirst();
        assertThat(c.max()).isEqualByComparingTo("2400000000");
        assertThat(c.status()).isEqualTo(ConditionStatus.CONFIRMED);
    }
    @Test void testTypeDetectionUsesLowEffort() {
        var type=mock(kr.astra.testtype.entity.TestType.class);
        when(type.getCode()).thenReturn("VIBRATION"); when(type.getNameKo()).thenReturn("진동"); when(type.getKeywords()).thenReturn(List.of("진동"));
        when(types.findAllOrdered()).thenReturn(List.of(type));
        when(llm.complete(any())).thenReturn(new LlmClient.Reply("{\"testTypeCode\":null}","m","r",1,1));
        service.extract("무언가 시험",null);
        var request=org.mockito.ArgumentCaptor.forClass(LlmClient.Request.class);
        verify(llm).complete(request.capture());
        assertThat(request.getValue().effort()).isEqualTo("low");
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
