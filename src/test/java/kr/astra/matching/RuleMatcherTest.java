package kr.astra.matching;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import kr.astra.equipment.dto.EquipmentSpecView;
import kr.astra.equipment.entity.SpecSourceType;
import kr.astra.matching.entity.*;
import kr.astra.matching.service.RuleMatcher;
import kr.astra.requirement.dto.RequirementSnapshot.Condition;
import kr.astra.requirement.entity.*;
import kr.astra.testtype.dto.ConditionRuleResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RuleMatcherTest {
    final RuleMatcher matcher=new RuleMatcher();
    static BigDecimal n(String value) { return value == null ? null : new BigDecimal(value); }
    ConditionRuleResponse rule(ConditionComparator comparator,String unit) {
        return new ConditionRuleResponse("requirement","요구",ConditionValueType.NUMBER,unit,true,"spec",comparator);
    }
    Condition condition(String value,String min,String max,String unit,ConditionStatus status) {
        return new Condition("requirement","요구",n(value),n(min),n(max),null,unit,status);
    }
    EquipmentSpecView spec(String value,String min,String max,String unit,SpecSourceType source) {
        return new EquipmentSpecView("spec","사양",n(value),n(min),n(max),null,unit,source);
    }
    @ParameterizedTest
    @CsvSource({"MAX_LIMIT,15,15,MET","MAX_LIMIT,15,14.999,NOT_MET","MAX_LIMIT,15,16,MET",
            "MIN_LIMIT,0.01,0.001,MET","MIN_LIMIT,0.01,0.01,MET","MIN_LIMIT,0.01,0.011,NOT_MET"})
    void limitDirectionAndBoundary(ConditionComparator comparator,String required,String actual,CheckResult expected) {
        var result=matcher.evaluate(List.of(rule(comparator,"mm")),List.of(condition(required,null,null,"mm",ConditionStatus.CONFIRMED)),
                List.of(spec(actual,null,null,"mm",SpecSourceType.REAL)));
        assertThat(result.checks().getFirst().result()).isEqualTo(expected);
        assertThat(result.verdict()).isEqualTo(expected == CheckResult.MET ? MatchVerdict.SUITABLE : MatchVerdict.UNSUITABLE);
    }
    @Test void rangeIncludesBoundariesAndConvertsGhzToHz() {
        var rule=rule(ConditionComparator.RANGE_WITHIN,"Hz");
        var condition=condition(null,"20","2000","Hz",ConditionStatus.CONFIRMED);
        var result=matcher.evaluate(List.of(rule),List.of(condition),List.of(spec(null,"0.00000002","0.000002","GHz",SpecSourceType.MOCK)));
        assertThat(result.checks().getFirst().result()).isEqualTo(CheckResult.MET);
        assertThat(result.usedMockData()).isTrue();
    }
    @Test void realWinsEvenWhenMockWouldPassAndInputIsUnsorted() {
        var result=matcher.evaluate(List.of(rule(ConditionComparator.MAX_LIMIT,"kg")),
                List.of(condition("15",null,null,"kg",ConditionStatus.CONFIRMED)),
                List.of(spec("100",null,null,"kg",SpecSourceType.MOCK),spec("10",null,null,"kg",SpecSourceType.REAL)));
        assertThat(result.verdict()).isEqualTo(MatchVerdict.UNSUITABLE);
        assertThat(result.usedMockData()).isFalse();
    }
    @Test void invalidRealDoesNotFallBackToMock() {
        var result=matcher.evaluate(List.of(rule(ConditionComparator.MAX_LIMIT,"kg")),
                List.of(condition("15",null,null,"kg",ConditionStatus.CONFIRMED)),
                List.of(spec("100",null,null,"kg",SpecSourceType.MOCK),spec(null,null,null,"kg",SpecSourceType.REAL)));
        assertThat(result.checks().getFirst().result()).isEqualTo(CheckResult.UNKNOWN);
        assertThat(result.usedMockData()).isFalse();
    }
    @Test void invalidNegativeResolutionIsNotConsideredBetter() {
        var result=matcher.evaluate(List.of(rule(ConditionComparator.MIN_LIMIT,"mm")),
                List.of(condition("0.01",null,null,"mm",ConditionStatus.CONFIRMED)),
                List.of(spec("-1",null,null,"mm",SpecSourceType.REAL)));
        assertThat(result.checks().getFirst().result()).isEqualTo(CheckResult.UNKNOWN);
    }
    @Test void missingUnconfirmedIncompatibleUnitsAndNoneAreNeverMet() {
        var confirmed=condition("10",null,null,"kg",ConditionStatus.CONFIRMED);
        var actual=spec("20",null,null,"kg",SpecSourceType.REAL);
        assertThat(matcher.evaluate(List.of(rule(ConditionComparator.MAX_LIMIT,"kg")),List.of(),List.of(actual)).score()).isZero();
        assertThat(matcher.evaluate(List.of(rule(ConditionComparator.MAX_LIMIT,"kg")),List.of(confirmed),List.of()).score()).isZero();
        assertThat(matcher.evaluate(List.of(rule(ConditionComparator.NONE,"kg")),List.of(confirmed),List.of(actual)).checks())
                .extracting(c -> c.result()).containsExactly(CheckResult.UNKNOWN);
        var unconfirmed=condition("10",null,null,"kg",ConditionStatus.UNCONFIRMED);
        assertThat(matcher.evaluate(List.of(rule(ConditionComparator.MAX_LIMIT,"kg")),List.of(unconfirmed),List.of(actual)).score()).isZero();
        assertThat(matcher.evaluate(List.of(rule(ConditionComparator.MAX_LIMIT,"Hz")),List.of(confirmed),List.of(actual)).score()).isZero();
    }
    @Test void malformedRangeAndSingleValueEquipmentAreUnknown() {
        var rule=rule(ConditionComparator.RANGE_WITHIN,"Hz");
        var requested=condition("20",null,null,"Hz",ConditionStatus.CONFIRMED);
        for (var actual: List.of(spec(null,"100","10","Hz",SpecSourceType.REAL),spec("30",null,null,"Hz",SpecSourceType.REAL))) {
            assertThat(matcher.evaluate(List.of(rule),List.of(requested),List.of(actual)).checks().getFirst().result()).isEqualTo(CheckResult.UNKNOWN);
        }
    }
    @Test void optionalAbsentExcludedButRequiredMissingAndNoneCountInDenominator() {
        var required=rule(ConditionComparator.MAX_LIMIT,"kg");
        var absent=new ConditionRuleResponse("missing","누락",ConditionValueType.NUMBER,"kg",true,"spec",ConditionComparator.MAX_LIMIT);
        var optional=new ConditionRuleResponse("optional","선택",ConditionValueType.NUMBER,"kg",false,"spec",ConditionComparator.MAX_LIMIT);
        var result=matcher.evaluate(List.of(required,absent,optional),List.of(condition("10",null,null,"kg",ConditionStatus.CONFIRMED)),
                List.of(spec("20",null,null,"kg",SpecSourceType.REAL)));
        assertThat(result.score()).isEqualByComparingTo("50");
        assertThat(result.checks()).hasSize(2);
        assertThat(result.verdict()).isEqualTo(MatchVerdict.PARTIAL);
        assertThat(matcher.evaluate(List.of(),List.of(),List.of()).verdict()).isEqualTo(MatchVerdict.UNKNOWN);
    }
}
