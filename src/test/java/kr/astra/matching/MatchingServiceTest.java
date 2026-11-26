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
    /** 콜백 실행 중에만 inTx=true 인 가짜 트랜잭션: AI 설명이 트랜잭션 밖에서 호출되는지 확인 */
    final java.util.concurrent.atomic.AtomicBoolean inTx=new java.util.concurrent.atomic.AtomicBoolean();
    final org.springframework.transaction.support.TransactionOperations tx=new org.springframework.transaction.support.TransactionOperations() {
        @Override public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
            inTx.set(true);
            try { return action.doInTransaction(null); } finally { inTx.set(false); }
        }
    };
    final MatchingService service=new MatchingService(requirements,equipment,details,new RuleMatcher(),runs,results,explanations,tx);
    RequirementSnapshot snapshot;
    @BeforeEach void setup() {
        snapshot=new RequirementSnapshot(7L,"TEST","시험",null,null,null,
                List.of(new RequirementSnapshot.Condition("mass","질량",BigDecimal.TEN,null,null,null,"kg",ConditionStatus.CONFIRMED)));
        when(requirements.snapshot(user,7L)).thenReturn(snapshot);
        when(requirements.rules("TEST")).thenReturn(List.of(new ConditionRuleResponse("mass","질량",ConditionValueType.NUMBER,"kg",true,"payload",ConditionComparator.MAX_LIMIT)));
        when(runs.saveAndFlush(any())).thenAnswer(i -> {
            assertThat(inTx).as("매칭 결과 저장은 짧은 트랜잭션 안에서").isTrue();
            MatchRun r=i.getArgument(0); ReflectionTestUtils.setField(r,"id",3L); return r;
        });
        when(explanations.explain(any())).thenReturn(Map.of(9L,"설명",2L,"설명"));
    }
    EquipmentCandidate candidate(Long id) { return new EquipmentCandidate(id,"E"+id,"장비",2L,"기관",null,false); }
    void specs(SpecSourceType source) {
        when(equipment.getSpecs(anyCollection())).thenAnswer(i -> {
            Map<Long,List<EquipmentSpecView>> map=new HashMap<>();
            for (Object id:(Collection<?>)i.getArgument(0))
                map.put((Long)id,List.of(new EquipmentSpecView("payload","하중",BigDecimal.TEN,null,null,null,"kg",source)));
            return map;
        });
    }
    @Test void stableTieBreakPersistsSnapshotVersionChecksAndMockUsage() {
        when(equipment.findCandidates("TEST",null)).thenReturn(List.of(candidate(9L),candidate(2L)));
        specs(SpecSourceType.MOCK);
        var result=service.run(user,7L,null,5);
        assertThat(result.results()).extracting(r -> r.equipment().id()).containsExactly(2L,9L);
        assertThat(result.results()).allSatisfy(r -> { assertThat(r.usedMockData()).isTrue(); assertThat(r.checks()).hasSize(1); });
        verify(runs).saveAndFlush(argThat(r -> r.getRequirementSnapshot().equals(snapshot) && r.getAlgorithmVersion().equals("rule-v2")));
    }
    @Test void aiExplanationFailureStillReturnsRuleResultsWithoutExplanation() {
        // AI 설명은 부가 정보. 실패해도 규칙 판정 결과는 그대로 저장·응답 (시연 중 Gemini 503/429 대비)
        when(equipment.findCandidates("TEST",null)).thenReturn(List.of(candidate(9L)));
        specs(SpecSourceType.REAL);
        when(explanations.explain(any())).thenThrow(new BusinessException(ErrorCode.AI_UPSTREAM_ERROR));
        var result=service.run(user,7L,null,5);
        assertThat(result.results()).singleElement().satisfies(r -> {
            assertThat(r.verdict()).isEqualTo(MatchVerdict.SUITABLE);
            assertThat(r.reason()).contains("질량: 충족").contains(MatchingService.DISCLAIMER).doesNotContain("null");
        });
        verify(runs).saveAndFlush(any());
    }
    @Test void disclaimerAppearsOnceEvenWhenAiExplanationRepeatsIt() {
        when(equipment.findCandidates("TEST",null)).thenReturn(List.of(candidate(9L)));
        specs(SpecSourceType.REAL);
        when(explanations.explain(any())).thenReturn(Map.of(9L,"질량 조건을 충족합니다. " + MatchingService.DISCLAIMER));
        var reason=service.run(user,7L,null,5).results().get(0).reason();
        assertThat(reason).contains("질량 조건을 충족합니다.");
        assertThat(reason.split(java.util.regex.Pattern.quote(MatchingService.DISCLAIMER),-1)).hasSize(2);
    }
    @Test void ownOrganizationEquipmentIsNotRecommended() {
        // 장비 보유 조직(기관 2) 소속 회원이 매칭하면 자기 조직 장비는 의뢰할 수 없으므로 후보에서 빠짐
        var member=new CurrentUser(1L,UserRole.INSTITUTION_MANAGER,2L);
        when(requirements.snapshot(member,7L)).thenReturn(snapshot);
        when(equipment.findCandidates("TEST",null)).thenReturn(List.of(candidate(9L),
                new EquipmentCandidate(2L,"E2","장비",5L,"다른 조직",null,false)));
        specs(SpecSourceType.REAL);
        var result=service.run(member,7L,null,5);
        assertThat(result.results()).extracting(r -> r.equipment().id()).containsExactly(2L);
    }
    @Test void aiExplanationRunsWithoutHoldingATransaction() {
        // 최대 45초 걸리는 AI 설명 동안 DB 커넥션(풀 10개)을 잡고 있지 않음
        when(equipment.findCandidates("TEST",null)).thenReturn(List.of(candidate(9L)));
        specs(SpecSourceType.REAL);
        when(explanations.explain(any())).thenAnswer(i -> {
            assertThat(inTx).as("AI 설명은 트랜잭션 밖에서").isFalse();
            return Map.of(9L,"설명");
        });
        service.run(user,7L,null,5);
        verify(explanations).explain(any());
        verify(runs).saveAndFlush(any());
        assertThat(java.util.Arrays.stream(MatchingService.class.getMethods()).filter(m -> m.getName().equals("run")).findFirst().orElseThrow()
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class).propagation())
                .isEqualTo(org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED);
    }
    @Test void specsAccreditationAndSummariesAreLoadedInBatches() {
        // 후보마다 getSpecs·accredited, 결과마다 getDetail(쿼리 ~5개)을 부르던 N+1 제거
        when(equipment.findCandidates("TEST",null)).thenReturn(List.of(candidate(9L),candidate(2L),candidate(4L)));
        specs(SpecSourceType.REAL);
        service.run(user,7L,null,5);
        verify(equipment).getSpecs(anyCollection());
        verify(details).accredited(anyCollection(),eq("TEST"));
        verify(equipment,never()).getSpecs(anyLong());
        verify(details,never()).accredited(anyLong(),anyString());
        var run=new MatchRun(7L,1L,snapshot);
        run.getResults().add(new MatchResult(run,9L,1,BigDecimal.TEN,MatchVerdict.SUITABLE,false,"reason",List.of()));
        run.getResults().add(new MatchResult(run,2L,2,BigDecimal.ONE,MatchVerdict.PARTIAL,false,"reason",List.of()));
        when(runs.findById(3L)).thenReturn(Optional.of(run));
        when(equipment.findSummaries(anyCollection())).thenReturn(Map.of(9L,candidate(9L)));
        var response=service.get(user,3L);
        assertThat(response.results()).extracting(r -> r.equipment().id()).containsExactly(9L,2L); // 비활성(2) 은 id 만 남김
        assertThat(response.results().get(1).equipment().name()).isNull();
        verify(equipment).findSummaries(anyCollection());
        verify(details,never()).getDetail(anyLong());
    }
    @Test void candidateCountAppliesSameOwnOrganizationExclusionAsMatching() {
        var member=new CurrentUser(1L,UserRole.INSTITUTION_MANAGER,2L);
        when(equipment.countCandidates("TEST",null,2L)).thenReturn(4L);
        assertThat(service.countCandidates(member,"TEST",null)).isEqualTo(4L);
        when(equipment.countCandidates("TEST",null,null)).thenReturn(6L);
        assertThat(service.countCandidates(new CurrentUser(9L,UserRole.ADMIN,2L),"TEST",null)).isEqualTo(6L);
        assertThat(service.countCandidates(user,"TEST",null)).isEqualTo(6L);
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
