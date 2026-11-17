package kr.astra.requirement;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import kr.astra.global.exception.*;
import kr.astra.requirement.dto.ConditionInput;
import kr.astra.requirement.entity.*;
import kr.astra.requirement.service.*;
import kr.astra.testtype.dto.ConditionRuleResponse;
import org.junit.jupiter.api.Test;
class ConditionValidatorTest {
    final ConditionValidator validator=new ConditionValidator();
    final List<ConditionRuleResponse> rules=List.of(new ConditionRuleResponse("mass","질량",ConditionValueType.NUMBER,"kg",true,"payload",ConditionComparator.MAX_LIMIT),
            new ConditionRuleResponse("temperature","온도",ConditionValueType.RANGE,"°C",true,"temperature",ConditionComparator.RANGE_WITHIN));
    ConditionInput mass(String value,String unit,ConditionStatus status) {
        return new ConditionInput("mass",value == null ? null : new BigDecimal(value),null,null,null,unit,status,null,null);
    }
    @Test void convertsCompatibleUnitsAndAllowsNegativeTemperature() {
        assertThat(validator.validate(List.of(mass("15000","g",ConditionStatus.CONFIRMED)),rules).getFirst().value()).isEqualByComparingTo("15");
        var c=new ConditionInput("temperature",null,new BigDecimal("-40"),new BigDecimal("80"),null,"°C",ConditionStatus.CONFIRMED,null,null);
        assertThat(validator.validate(List.of(c),rules)).hasSize(1);
    }
    @Test void unitAliasesAreNormalizedToRuleUnit() {
        var withHumidity=new ArrayList<>(rules);
        withHumidity.add(new ConditionRuleResponse("humidity","습도",ConditionValueType.RANGE,"%RH",false,"humidity",ConditionComparator.RANGE_WITHIN));
        var temperature=new ConditionInput("temperature",null,new BigDecimal("-40"),new BigDecimal("85"),null,"℃",ConditionStatus.CONFIRMED,null,null);
        var humidity=new ConditionInput("humidity",new BigDecimal("95"),null,null,null,"%",ConditionStatus.CONFIRMED,null,null);
        assertThat(validator.validate(List.of(temperature,humidity),withHumidity)).extracting(ConditionInput::unit)
                .containsExactly("°C","%RH");
        assertThat(UnitConverter.canonical("배","x")).isEqualTo("x");
        assertThat(UnitConverter.canonical("킬로그램","kg")).isEqualTo("kg");
        assertThat(UnitConverter.canonical("%","kg")).isEqualTo("%"); // %RH 규칙일 때만 습도로 해석
    }
    @Test void rejectsUnknownDuplicateNegativeAndIncompatibleValues() {
        var valid=mass("15","kg",ConditionStatus.CONFIRMED);
        for (var inputs: List.of(List.of(valid,valid),List.of(mass("-1","kg",ConditionStatus.CONFIRMED)),
                List.of(mass("1","Hz",ConditionStatus.CONFIRMED)),List.of(mass(null,"kg",ConditionStatus.CONFIRMED)),
                List.of(mass("1","kg",ConditionStatus.MISSING)))) {
            assertThatThrownBy(() -> validator.validate(inputs,rules)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> validator.validate(List.of(valid),List.of())).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsInvertedRangeAndOutOfRangeConfidence() {
        var range=new ConditionInput("temperature",null,BigDecimal.TEN,BigDecimal.ONE,null,"°C",ConditionStatus.CONFIRMED,null,null);
        var confidence=new ConditionInput("mass",BigDecimal.ONE,null,null,null,"kg",ConditionStatus.CONFIRMED,new BigDecimal("1.01"),null);
        assertThatThrownBy(() -> validator.validate(List.of(range),rules)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validate(List.of(confidence),rules)).isInstanceOf(BusinessException.class);
    }
    @Test void missingIsExplicitAndHasNoValue() {
        assertThat(validator.validate(List.of(mass(null,null,ConditionStatus.MISSING)),rules).getFirst().unit()).isEqualTo("kg");
        assertThatThrownBy(() -> UnitConverter.convert(BigDecimal.ONE,"Hz","kg")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void jsonbDecimalScaleDoesNotMakeAnUnchangedSnapshotStale() {
        var a=new kr.astra.requirement.dto.RequirementSnapshot(7L,"VIBRATION",null,null,null,null,List.of(
                new kr.astra.requirement.dto.RequirementSnapshot.Condition("mass","질량",new BigDecimal("10.00"),null,null,null,"kg",ConditionStatus.CONFIRMED)));
        var b=new kr.astra.requirement.dto.RequirementSnapshot(7L,"VIBRATION",null,null,null,null,List.of(
                new kr.astra.requirement.dto.RequirementSnapshot.Condition("mass","질량",new BigDecimal("1E+1"),null,null,null,"kg",ConditionStatus.CONFIRMED)));
        assertThat(RequirementSnapshots.same(a,b)).isTrue();
    }
}
