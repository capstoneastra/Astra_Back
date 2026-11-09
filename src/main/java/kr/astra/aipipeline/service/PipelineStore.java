package kr.astra.aipipeline.service;
import java.util.function.Consumer;
import kr.astra.aipipeline.dto.PipelineResponse;
import kr.astra.aipipeline.entity.*;
import kr.astra.aipipeline.repository.PipelineRepository;
import kr.astra.chat.service.ChatPersistence;
import kr.astra.global.exception.*;
import kr.astra.global.security.*;
import kr.astra.user.entity.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class PipelineStore {
    private final PipelineRepository repository;
    private final ChatPersistence chats;
    public record Work(Long chatSessionId,String inputText,PipelineResponse response) {}
    record Recovery(Long id,Long userId) {}
    java.util.List<Recovery> recoverableAfter(Long id) {
        return repository.findTop100ByIdGreaterThanAndStatusInOrderByIdAsc(id,
                java.util.List.of(PipelineStatus.PENDING,PipelineStatus.RUNNING)).stream()
                .map(r -> new Recovery(r.getId(),r.getUserId())).toList();
    }
    @Transactional
    void rejectRecovery(Long id) {
        repository.lockById(id).ifPresent(r -> r.fail("실행 소유자 계정의 활성 상태 또는 권한을 확인할 수 없습니다."));
    }
    @Transactional
    public PipelineResponse create(CurrentUser user,Long chatSessionId,String text) {
        AccessGuard.requireRole(user,UserRole.COMPANY_USER);
        if ((chatSessionId == null) == (text == null || text.isBlank())) throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "chatSessionId 또는 inputText 중 하나를 입력해 주세요.",null);
        if (chatSessionId != null) chats.ownerId(user,chatSessionId);
        return repository.saveAndFlush(new PipelineRun(user.userId(),chatSessionId,text)).response();
    }
    public PipelineResponse get(CurrentUser user,Long id) { return owned(user,id,false).response(); }
    public Work work(CurrentUser user,Long id) {
        var r=owned(user,id,false);
        return new Work(r.getChatSessionId(),r.getInputText(),r.response());
    }
    @Transactional
    public PipelineResponse resume(CurrentUser user,Long id) {
        var r=owned(user,id,true); r.resume(); return r.response();
    }
    @Transactional
    public PipelineResponse cancel(CurrentUser user,Long id) {
        var r=owned(user,id,true); r.cancel(); return r.response();
    }
    @Transactional
    public boolean running(CurrentUser user,Long id,PipelineStep expected) {
        var r=owned(user,id,true);
        if (!r.at(expected)) return false;
        r.running(); return true;
    }
    /** The checkpoint and domain writes join one transaction; cancel serializes on this same row. */
    @Transactional
    public void advance(CurrentUser user,Long id,PipelineStep expected,Consumer<PipelineRun> action) {
        var r=owned(user,id,true);
        if (r.at(expected)) action.accept(r);
    }
    @Transactional
    public void fail(CurrentUser user,Long id,PipelineStep expected,String message) {
        var r=owned(user,id,true);
        if (r.at(expected)) r.fail(message);
    }
    private PipelineRun owned(CurrentUser user,Long id,boolean lock) {
        AccessGuard.requireRole(user,UserRole.COMPANY_USER);
        var r=(lock ? repository.lockById(id) : repository.findById(id))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        AccessGuard.requireOwner(user,r.getUserId()); return r;
    }
}
