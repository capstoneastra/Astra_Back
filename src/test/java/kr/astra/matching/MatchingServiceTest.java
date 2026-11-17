package kr.astra.matching;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import kr.astra.equipment.dto.*;
import kr.astra.equipment.entity.SpecSourceType;
import kr.astra.equipment.service.*;
import kr.astra.global.exception.*;
import kr.astra.global.security.CurrentUser;
import kr.astra.matching.entity.*;
import kr.astra.matching.repository.*;
import kr.astra.matching.service.*;
import kr.astra.requirement.dto.RequirementSnapshot;
import kr.astra.requirement.entity.*;
import kr.astra.requirement.service.RequirementApplicationService;
import kr.astra.testtype.dto.ConditionRuleResponse;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
class MatchingServiceTest {
    final CurrentUser user=new CurrentUser(1L,UserRole.COMPANY_USER,null);
    final RequirementApplicationService requirements=mock(RequirementApplicationService.class);
    final EquipmentQueryService equipment=mock(EquipmentQueryService.class);
    final EquipmentService details=mock(EquipmentService.class);
    final MatchRunRepository runs=mock(MatchRunRepository.class);
    final MatchResultRepository results=mock(MatchResultRepository.class);
    final kr.astra.llm.service.MatchExplanationService explanations=mock(kr.astra.llm.service.MatchExplanationService.class);
    final MatchingService service=new MatchingService(requirements,equipment,details,new RuleMatcher(),runs,results,explanations);
    RequirementSnapshot snapshot;
    @BeforeEach void setup() {
        snapshot=new RequirementSnapshot(7L,"TEST","시험",null,null,null,
                List.of(new RequirementSnapshot.Condition("mass","질량",BigDecimal.TEN,null,null,null,"kg",ConditionStatus.CONFIRMED)));
        when(requirements.snapshot(user,7L)).thenReturn(snapshot);
        when(requirements.rules("TEST")).thenReturn(List.of(new ConditionRuleResponse("mass","질량",ConditionValueType.NUMBER,"kg",true,"payload",ConditionComparator.MAX_LIMIT)));
        when(runs.saveAndFlush(any())).thenAnswer(i -> { MatchRun r=i.getArgument(0); ReflectionTestUtils.setField(r,"id",3L); return r; });
        when(explanations.explain(any())).thenReturn(Map.of(9L,"설명",2L,"설명"));
    }
    EquipmentCandidate candidate(Long id) { return new EquipmentCandidate(id,"E"+id,"장비",2L,"기관",null,false); }
    @Test void stableTieBreakPersistsSnapshotVersionChecksAndMockUsage() {
        when(equipment.findCandidates("TEST",null)).thenReturn(List.of(candidate(9L),candidate(2L)));
        when(equipment.getSpecs(anyLong())).thenReturn(List.of(new EquipmentSpecView("payload","하중",BigDecimal.TEN,null,null,null,"kg",SpecSourceType.MOCK)));
        var result=service.run(user,7L,null,5);
        assertThat(result.results()).extracting(r -> r.equipment().id()).containsExactly(2L,9L);
        assertThat(result.results()).allSatisfy(r -> { assertThat(r.usedMockData()).isTrue(); assertThat(r.checks()).hasSize(1); });
        verify(runs).saveAndFlush(argThat(r -> r.getRequirementSnapshot().equals(snapshot) && r.getAlgorithmVersion().equals("rule-v2")));
    }
    @Test void ownOrganizationEquipmentIsNotRecommended() {
        // 장비 보유 조직(기관 2) 소속 회원이 매칭하면 자기 조직 장비는 의뢰할 수 없으므로 후보에서 빠짐
        var member=new CurrentUser(1L,UserRole.INSTITUTION_MANAGER,2L);
        when(requirements.snapshot(member,7L)).thenReturn(snapshot);
        when(equipment.findCandidates("TEST",null)).thenReturn(List.of(candidate(9L),
                new EquipmentCandidate(2L,"E2","장비",5L,"다른 조직",null,false)));
        when(equipment.getSpecs(anyLong())).thenReturn(List.of(new EquipmentSpecView("payload","하중",BigDecimal.TEN,null,null,null,"kg",SpecSourceType.REAL)));
        var result=service.run(member,7L,null,5);
        assertThat(result.results()).extracting(r -> r.equipment().id()).containsExactly(2L);
    }
    @Test void linkedResultChecksOwnerBeforeRelationshipAndRejectsStaleSnapshot() {
        var run=new MatchRun(8L,2L,snapshot);
        var result=new MatchResult(run,48L,1,BigDecimal.TEN,MatchVerdict.SUITABLE,false,"reason",List.of());
        when(results.findById(31L)).thenReturn(Optional.of(result));
        when(requirements.get(user,8L)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.validateSelection(user,7L,48L,31L))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        var ownRun=new MatchRun(7L,1L,new RequirementSnapshot(7L,"TEST","old",null,null,null,List.of()));
        when(results.findById(32L)).thenReturn(Optional.of(new MatchResult(ownRun,48L,1,BigDecimal.TEN,MatchVerdict.SUITABLE,false,"reason",List.of())));
        assertThatThrownBy(() -> service.validateSelection(user,7L,48L,32L))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
        verifyNoInteractions(details);
    }
}
