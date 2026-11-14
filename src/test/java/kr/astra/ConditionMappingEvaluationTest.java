package kr.astra;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import kr.astra.llm.service.ConditionExtractionService;
import kr.astra.requirement.entity.ConditionStatus;
import kr.astra.testtype.service.TestTypeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/** 실제 공급자를 호출하는 별도 평가. 기본 test에서는 실행하지 않으며 모의 응답을 정확도로 보고하지 않는다. */
@SpringBootTest @Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named="ASTRA_RUN_LLM_EVAL", matches="true")
class ConditionMappingEvaluationTest {
    @Autowired ConditionExtractionService extraction;
    @Autowired TestTypeService types;
    @Autowired JsonMapper json;

    @Test void measuresExactTestTypeAndConditionKeysAgainstSeedCorpus() throws Exception {
        var cases=json.readTree(getClass().getResourceAsStream("/condition-mapping-cases.json"));
        assertThat(cases.size()).isEqualTo(25);
        int exact=0, valid=0;
        List<Map<String,Object>> outcomes=new ArrayList<>();
        for (var c:cases) {
            String input=c.get("input").asString(), expectedType=c.get("testTypeCode").asString();
            Set<String> wanted=new TreeSet<>(); c.get("keys").forEach(k -> wanted.add(k.asString()));
            Set<String> defined=new HashSet<>(); types.getConditionRules(expectedType).forEach(r -> defined.add(r.conditionKey()));
            assertThat(defined).containsAll(wanted);
            Map<String,Object> outcome=new LinkedHashMap<>(); outcome.put("input",input);
            outcome.put("expectedType",expectedType); outcome.put("expectedKeys",wanted);
            try {
                var actual=extraction.extract(input,null);
                Set<String> keys=new TreeSet<>();
                actual.conditions().stream().filter(v -> v.status()!=ConditionStatus.MISSING).forEach(v -> keys.add(v.key()));
                Set<String> allowed=new HashSet<>(); types.getConditionRules(actual.testTypeCode()).forEach(r -> allowed.add(r.conditionKey()));
                boolean allowedOutput=actual.testTypeCode()!=null && allowed.containsAll(keys);
                boolean equal=expectedType.equals(actual.testTypeCode()) && wanted.equals(keys);
                if (allowedOutput) valid++;
                if (equal) exact++;
                outcome.put("actualType",actual.testTypeCode()); outcome.put("actualKeys",keys); outcome.put("exact",equal);
            } catch (Exception e) {
                outcome.put("exact",false); outcome.put("errorType",e.getClass().getSimpleName());
            }
            outcomes.add(outcome);
        }
        Path report=Path.of("build/reports/condition-mapping.json"); Files.createDirectories(report.getParent());
        Files.writeString(report,json.writeValueAsString(Map.of("total",cases.size(),"exact",exact,
                "exactRate",exact/(double)cases.size(),"validCatalogRate",valid/(double)cases.size(),"cases",outcomes)));
        assertThat(exact).as("실제 LLM 시험유형+키 완전일치; 상세는 %s",report).isGreaterThanOrEqualTo(23);
    }
}
