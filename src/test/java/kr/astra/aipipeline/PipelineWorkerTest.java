package kr.astra.aipipeline;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import kr.astra.aipipeline.entity.*;
import kr.astra.aipipeline.repository.PipelineRepository;
import kr.astra.aipipeline.service.*;
import kr.astra.chat.service.ChatPersistence;
import kr.astra.equipment.dto.EquipmentCandidate;
import kr.astra.equipment.service.EquipmentQueryService;
import kr.astra.global.security.CurrentUser;
import kr.astra.llm.dto.AiDraftRequest;
import kr.astra.llm.service.*;
import kr.astra.matching.dto.MatchResponse;
import kr.astra.matching.entity.MatchVerdict;
import kr.astra.matching.service.MatchingService;
import kr.astra.requirement.dto.*;
import kr.astra.requirement.service.RequirementApplicationService;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.*;
class PipelineWorkerTest {
    final CurrentUser user=new CurrentUser(1L,UserRole.COMPANY_USER,null);
    final PipelineRepository repo=mock(PipelineRepository.class);
    final ChatPersistence chats=mock(ChatPersistence.class);
    final PipelineStore store=new PipelineStore(repo,chats);
    final RequirementApplicationService requirements=mock(RequirementApplicationService.class);
    final ConditionExtractionService extraction=mock(ConditionExtractionService.class);
    final MatchingService matching=mock(MatchingService.class);
    final AiDraftService drafts=mock(AiDraftService.class);
    final PipelineWorker worker=new PipelineWorker(store,chats,requirements,extraction,matching,drafts);
    PipelineRun run;
    @BeforeEach void setup() {
        run=new PipelineRun(1L,null,"시료 15kg 진동시험");
        when(repo.findById(9L)).thenReturn(Optional.of(run));
        when(repo.lockById(9L)).thenReturn(Optional.of(run));
    }
    void analyzed(boolean ready) {
        var analysis=mock(AnalysisResponse.class); when(analysis.readyForMatching()).thenReturn(ready);
        when(requirements.analyze(user,7L)).thenReturn(analysis);
    }
    void afterAnalysis() {
        when(requirements.snapshot(user,7L)).thenReturn(new RequirementSnapshot(7L,"VIBRATION","시험",null,null,null,List.of()));
        var equipmentSummary=new EquipmentCandidate(48L,"E48","장비",2L,"기관",null,false);
        when(matching.countCandidates(user,"VIBRATION",null)).thenReturn(1L);
        var result=new MatchResponse.Result(31L,1,java.math.BigDecimal.TEN,MatchVerdict.SUITABLE,false,equipmentSummary,"reason",List.of());
        var match=new MatchResponse(3L,7L,"rule-v1",null,List.of(result),"disclaimer");
        when(matching.run(user,7L,null,5)).thenReturn(match); when(matching.get(user,3L)).thenReturn(match);
        when(drafts.prepare(eq(user),any())).thenReturn(mock(AiDraftService.Prepared.class));
        when(drafts.create(eq(user),any(),any())).thenReturn(15L);
    }
    @Test void waitsResumesSameRequirementAndNeverRepeatsCompletedWrites() {
        when(extraction.extract(anyString(),isNull())).thenReturn(new ConditionExtractionService.Extraction("VIBRATION",List.of(),Map.of()));
        var requirement=mock(RequirementResponse.class); when(requirement.id()).thenReturn(7L);
        when(requirements.createExtracted(eq(user),any())).thenReturn(requirement);
        analyzed(false);
        worker.run(user,9L);
        assertThat(run.getStatus()).isEqualTo(PipelineStatus.WAITING_USER);
        verifyNoInteractions(matching,drafts);
        analyzed(true); afterAnalysis();
        store.resume(user,9L); worker.run(user,9L); worker.run(user,9L);
        verify(extraction,times(1)).extract(anyString(),isNull());
        verify(requirements,times(1)).createExtracted(eq(user),any());
        verify(matching,times(1)).run(user,7L,null,5);
        verify(matching,times(1)).countCandidates(user,"VIBRATION",null);
        verify(drafts,times(1)).create(eq(user),any(),any());
        assertThat(run.getDraftRequestId()).isEqualTo(15L);
        // BE-B 저장 결과(DRAFT + AI 1번 제출본)를 확인하고 성공 처리
        verify(drafts).confirmReceipt(15L);
        assertThat(run.getStatus()).isEqualTo(PipelineStatus.SUCCEEDED);
        assertThat(run.getCurrentStep()).isEqualTo(PipelineStep.DONE);
        assertThat(run.getErrorMessage()).isNull();
    }
    @Test void unconfirmedDraftReceiptFailsWithoutClaimingSuccess() {
        run.analyzed(7L,true); run.searched(1); run.matched(3L);
        afterAnalysis();
        when(drafts.confirmReceipt(15L)).thenThrow(new kr.astra.global.exception.BusinessException(
                kr.astra.global.exception.ErrorCode.INTERNAL_ERROR,"초안 저장 결과를 확인할 수 없습니다.",null));
        worker.run(user,9L);
        assertThat(run.getStatus()).isEqualTo(PipelineStatus.FAILED);
        assertThat(run.getDraftRequestId()).isNull();
    }
    @Test void canceledWhileExtractingDoesNotCreateRequirementOrOverwriteCancel() {
        when(extraction.extract(anyString(),isNull())).thenAnswer(i -> {
            store.cancel(user,9L); return new ConditionExtractionService.Extraction("VIBRATION",List.of(),Map.of());
        });
        worker.run(user,9L);
        assertThat(run.getStatus()).isEqualTo(PipelineStatus.CANCELED);
        verify(requirements,never()).createExtracted(any(),any());
        verifyNoInteractions(matching,drafts);
    }
    @Test void shutdownInterruptLeavesRunForRecoveryInsteadOfFailingIt() {
        // 서버 종료(shutdownNow)로 중단된 실행은 FAILED 가 아니라 RUNNING 으로 남아 PipelineRecovery 가 이어서 실행
        when(extraction.extract(anyString(),isNull())).thenAnswer(i -> {
            Thread.currentThread().interrupt();
            throw new kr.astra.global.exception.BusinessException(kr.astra.global.exception.ErrorCode.AI_UPSTREAM_ERROR);
        });
        try { worker.run(user,9L); }
        finally { Thread.interrupted(); }
        assertThat(run.getStatus()).isEqualTo(PipelineStatus.RUNNING);
        assertThat(run.getErrorMessage()).isNull();
    }
    @Test void matchingRunsOutsideTheRunLockSoCancelDuringAiExplanationWins() {
        // 매칭(최대 45초 AI 설명)은 ai_pipeline_runs 행 잠금 밖에서 실행하고, 결과 기록만 잠금 안에서 함
        run.analyzed(7L,true); run.searched(1);
        afterAnalysis();
        when(matching.run(user,7L,null,5)).thenAnswer(i -> {
            store.cancel(user,9L);
            return new MatchResponse(3L,7L,"rule-v2",null,List.of(),"disclaimer");
        });
        worker.run(user,9L);
        assertThat(run.getStatus()).isEqualTo(PipelineStatus.CANCELED);
        assertThat(run.getMatchRunId()).isNull();
    }
    @Test void canceledWhileDraftingNeverCallsBeB() {
        run.analyzed(7L,true); run.searched(1); run.matched(3L);
        afterAnalysis();
        when(drafts.prepare(eq(user),any())).thenAnswer(i -> {
            store.cancel(user,9L); return mock(AiDraftService.Prepared.class);
        });
        worker.run(user,9L);
        verify(drafts,never()).create(any(),any(),any());
        assertThat(run.getStatus()).isEqualTo(PipelineStatus.CANCELED);
        assertThat(run.getDraftRequestId()).isNull();
    }
}
