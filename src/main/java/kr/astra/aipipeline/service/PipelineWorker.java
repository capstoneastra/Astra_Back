package kr.astra.aipipeline.service;
import kr.astra.aipipeline.entity.*;
import kr.astra.chat.service.ChatPersistence;
import kr.astra.global.exception.*;
import kr.astra.global.security.CurrentUser;
import kr.astra.llm.dto.AiDraftRequest;
import kr.astra.llm.service.*;
import kr.astra.matching.entity.MatchVerdict;
import kr.astra.matching.service.MatchingService;
import kr.astra.requirement.dto.RequirementInput;
import kr.astra.requirement.service.RequirementApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class PipelineWorker {
    private final PipelineStore store;
    private final ChatPersistence chats;
    private final RequirementApplicationService requirements;
    private final ConditionExtractionService extraction;
    private final MatchingService matching;
    private final AiDraftService drafts;
    public void run(CurrentUser user,Long id) {
        while (!Thread.currentThread().isInterrupted()) {
            var work=store.work(user,id);
            var state=work.response();
            if (state.status() != PipelineStatus.PENDING && state.status() != PipelineStatus.RUNNING) return;
            PipelineStep step=state.currentStep();
            if (!store.running(user,id,step)) return;
            try {
                switch (step) {
                    case ANALYZE -> analyze(user,id,work);
                    case SEARCH -> {
                        // 목록을 다 만들지 않고 매칭과 같은 기준(자기 조직 제외)으로 수만 셈
                        var snapshot=requirements.snapshot(user,state.requirementId());
                        long count=matching.countCandidates(user,snapshot.testTypeCode(),null);
                        store.advance(user,id,step,r -> r.searched((int)Math.min(count,Integer.MAX_VALUE)));
                    }
                    case MATCH -> {
                        // AI 설명(최대 45초)이 있는 매칭은 실행 행 잠금 밖에서, 결과 기록만 잠금 안에서
                        var match=matching.run(user,state.requirementId(),null,5);
                        store.advance(user,id,step,r -> r.matched(match.id()));
                    }
                    case DRAFT -> draft(user,id,state.requirementId(),state.matchRunId());
                    default -> { return; }
                }
            } catch (RuntimeException e) {
                // 서버 종료(shutdownNow) 인터럽트면 실패로 기록하지 않음: RUNNING 으로 남겨 PipelineRecovery 가 재개
                if (Thread.currentThread().isInterrupted()) return;
                String reason=e instanceof BusinessException b ? b.getMessage() : "자동 추진 단계 처리에 실패했습니다.";
                store.fail(user,id,step,reason);
                return;
            }
        }
    }
    private void analyze(CurrentUser user,Long id,PipelineStore.Work work) {
        Long existing=work.response().requirementId();
        if (existing == null && work.chatSessionId() != null)
            existing=requirements.forSession(user,work.chatSessionId()).map(r -> r.id()).orElse(null);
        if (existing != null) {
            Long requirementId=existing;
            store.advance(user,id,PipelineStep.ANALYZE,r ->
                    r.analyzed(requirementId,requirements.analyze(user,requirementId).readyForMatching()));
            return;
        }
        var transcript=work.chatSessionId() == null ? null : chats.transcript(user,work.chatSessionId());
        var extracted=extraction.extract(transcript == null ? work.inputText() : transcript.userText(),null);
        if (extracted.testTypeCode() == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"시험유형을 명확히 입력해 주세요.",null);
        store.advance(user,id,PipelineStep.ANALYZE,r -> {
            var requirement=transcript == null
                    ? requirements.createExtracted(user,new RequirementInput(extracted.testTypeCode(),"AI 자동 추진 시험조건",null,null,null,extracted.conditions()))
                    : chats.saveRequirement(user,work.chatSessionId(),transcript,extracted);
            r.analyzed(requirement.id(),requirements.analyze(user,requirement.id()).readyForMatching());
        });
    }
    private void draft(CurrentUser user,Long id,Long requirementId,Long matchRunId) {
        var match=matching.get(user,matchRunId);
        var selected=match.results().stream().filter(r -> r.verdict() == MatchVerdict.SUITABLE || r.verdict() == MatchVerdict.PARTIAL)
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,"초안을 작성할 적합 후보가 없습니다.",null));
        var input=new AiDraftRequest(requirementId,selected.equipment().id(),selected.id());
        var prepared=drafts.prepare(user,input); // external LLM runs outside the row lock
        store.advance(user,id,PipelineStep.DRAFT,r -> {
            Long draftId=r.getDraftRequestId() != null ? r.getDraftRequestId() : drafts.create(user,input,prepared);
            // ID 만으로는 DRAFT + AI 제출본을 보장할 수 없음: 같은 트랜잭션에서 저장 결과를 확인한 뒤에만 성공 처리
            drafts.confirmReceipt(draftId);
            r.drafted(draftId);
        });
    }
}
