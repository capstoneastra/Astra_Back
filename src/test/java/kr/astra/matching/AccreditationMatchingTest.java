package kr.astra.matching;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.util.*;
import kr.astra.equipment.entity.EquipmentTestType;
import kr.astra.matching.service.RuleMatcher;
import kr.astra.matching.entity.CheckResult;
import kr.astra.requirement.dto.RequirementSnapshot.Condition;
import kr.astra.requirement.entity.*;
import kr.astra.testtype.dto.ConditionRuleResponse;
import org.junit.jupiter.api.Test;

class AccreditationMatchingTest {
    final RuleMatcher matcher=new RuleMatcher();
    final ConditionRuleResponse rule=new ConditionRuleResponse("accredited_required","공인성적서 필요 여부",ConditionValueType.TEXT,null,false,null,ConditionComparator.NONE);
    Condition condition(ConditionStatus status) { return new Condition("accredited_required","공인성적서 필요 여부",null,null,null,"필요",null,status); }
    @Test void accreditedUnknownExpiredAndUnconfirmedAreDistinct() {
        assertThat(matcher.evaluate(List.of(rule),List.of(condition(ConditionStatus.CONFIRMED)),List.of(),true).checks().getFirst().result()).isEqualTo(CheckResult.MET);
        assertThat(matcher.evaluate(List.of(rule),List.of(condition(ConditionStatus.CONFIRMED)),List.of(),false).checks().getFirst().result()).isEqualTo(CheckResult.NOT_MET);
        assertThat(matcher.evaluate(List.of(rule),List.of(condition(ConditionStatus.CONFIRMED)),List.of(),null).checks().getFirst().result()).isEqualTo(CheckResult.UNKNOWN);
        assertThat(matcher.evaluate(List.of(rule),List.of(condition(ConditionStatus.UNCONFIRMED)),List.of(),true).checks().getFirst().result()).isEqualTo(CheckResult.UNKNOWN);
        assertThat(matcher.evaluate(List.of(rule),List.of(),List.of(),true).checks()).isEmpty();
    }
    @Test void accreditationIsValidThroughItsLastDayAndUnknownIsNotFalse() {
        var t=EquipmentTestType.of(48L,"VIBRATION",null,null,null,null);
        var date=LocalDate.of(2026,11,20);
        assertThat(t.accreditedOn(date)).isNull();
        t.accreditation(true,"TEST-ONLY",date);
        assertThat(t.accreditedOn(date)).isTrue();
        assertThat(t.accreditedOn(date.plusDays(1))).isFalse();
    }
}
