package kr.astra.aipipeline.entity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.*;
import kr.astra.aipipeline.dto.PipelineResponse;
import kr.astra.global.exception.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Getter @Entity @Table(name = "ai_pipeline_runs") @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PipelineRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long userId;
    private Long chatSessionId;
    @Column(columnDefinition = "text") private String inputText;
    private Long requirementId;
    private Long matchRunId;
    private Long draftRequestId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PipelineStatus status = PipelineStatus.PENDING;
    @Enumerated(EnumType.STRING) @Column(length = 20) private PipelineStep currentStep = PipelineStep.ANALYZE;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private List<PipelineResponse.Step> stepsLog;
    @Column(columnDefinition = "text") private String errorMessage;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    @CreationTimestamp @Column(nullable = false, updatable = false) private OffsetDateTime createdAt;
    public PipelineRun(Long userId,Long chatSessionId,String inputText) {
        this.userId=userId; this.chatSessionId=chatSessionId; this.inputText=inputText;
        stepsLog = List.of(PipelineStep.ANALYZE,PipelineStep.SEARCH,PipelineStep.MATCH,PipelineStep.DRAFT).stream()
                .map(s -> new PipelineResponse.Step(s,"PENDING",null)).toList();
    }
    public boolean active() { return status == PipelineStatus.PENDING || status == PipelineStatus.RUNNING; }
    public boolean at(PipelineStep expected) { return active() && currentStep == expected; }
    public void running() {
        if (!active()) return;
        status=PipelineStatus.RUNNING;
        if (startedAt == null) startedAt=OffsetDateTime.now();
        log(currentStep,"RUNNING",null);
    }
    public void analyzed(Long requirementId, boolean ready) {
        this.requirementId=requirementId;
        if (!ready) {
            status=PipelineStatus.WAITING_USER;
            log(PipelineStep.ANALYZE,"PENDING","필수 또는 미확인 조건을 보완해 주세요.");
        } else next(PipelineStep.SEARCH,"조건 분석 완료");
    }
    public void searched(int count) { next(PipelineStep.MATCH,"후보 장비 " + count + "대"); }
    public void matched(Long matchRunId) { this.matchRunId=matchRunId; next(PipelineStep.DRAFT,"매칭 결과 저장 완료"); }
    public void draftAwaitingReceipt(Long id, String reason) { this.draftRequestId=id; fail(reason); }
    public void resume() {
        if (status != PipelineStatus.WAITING_USER) throw new BusinessException(ErrorCode.REQUEST_INVALID_STATUS);
        status=PipelineStatus.PENDING;
        errorMessage=null;
    }
    public void cancel() {
        if (status == PipelineStatus.CANCELED) return;
        if (status == PipelineStatus.SUCCEEDED || status == PipelineStatus.FAILED)
            throw new BusinessException(ErrorCode.REQUEST_INVALID_STATUS);
        status=PipelineStatus.CANCELED;
        finishedAt=OffsetDateTime.now();
        log(currentStep,"CANCELED","사용자가 중단했습니다.");
    }
    public void fail(String reason) {
        if (!active()) return;
        status=PipelineStatus.FAILED; errorMessage=reason; finishedAt=OffsetDateTime.now();
        log(currentStep,"FAILED",reason);
    }
    private void next(PipelineStep next,String message) {
        log(currentStep,"DONE",message); currentStep=next;
    }
    private void log(PipelineStep step,String state,String message) {
        stepsLog=stepsLog.stream().map(s -> s.step() == step ? new PipelineResponse.Step(step,state,message) : s).toList();
    }
    public PipelineResponse response() {
        return new PipelineResponse(id,status,currentStep,List.copyOf(stepsLog),requirementId,matchRunId,draftRequestId,errorMessage);
    }
}
