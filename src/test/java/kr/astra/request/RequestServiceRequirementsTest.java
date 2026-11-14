package kr.astra.request;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import kr.astra.request.service.RequestContent;
import kr.astra.global.exception.BusinessException;
import org.junit.jupiter.api.Test;

class RequestServiceRequirementsTest {
    final RequestContent content=new RequestContent(tools.jackson.databind.json.JsonMapper.builder().build());
    @Test void acceptsExplicitFalseAndPreservesMissingAsUnknown() {
        var fields=new LinkedHashMap<String,Object>();
        fields.put("accreditedRequired",false); fields.put("operatorSupportRequested",true); fields.put("securityOrExportControlled",null);
        assertThatCode(() -> content.validate(Map.of("serviceRequirements",fields))).doesNotThrowAnyException();
        assertThatCode(() -> content.validate(Map.of())).doesNotThrowAnyException();
        assertThat(content.isFilled(Map.of("serviceRequirements",fields),"serviceRequirements.accreditedRequired")).isTrue();
        assertThat(content.isFilled(Map.of("serviceRequirements",fields),"serviceRequirements.securityOrExportControlled")).isFalse();
    }
    @Test void rejectsInventedFieldsAndStringBooleans() {
        assertThatThrownBy(() -> content.validate(Map.of("serviceRequirements",Map.of("accreditedRequired","true"))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> content.validate(Map.of("serviceRequirements",Map.of("unknown",true))))
                .isInstanceOf(BusinessException.class);
    }
}
