package kr.astra.aipipeline.service;
import kr.astra.aipipeline.dto.PipelineResponse;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.security.CurrentUser;
import kr.astra.llm.client.AiTaskExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class PipelineService {
    private final PipelineStore store;
    private final PipelineWorker worker;
    private final AiTaskExecutor executor;
    public PipelineResponse start(CurrentUser user,Long chatSessionId,String text) {
        var run=store.create(user,chatSessionId,text);
        enqueue(user,run);
        return run;
    }
    public PipelineResponse resume(CurrentUser user,Long id) {
        var run=store.resume(user,id);
        enqueue(user,run);
        return run;
    }
    void recover(CurrentUser user,Long id) { enqueue(user,store.get(user,id)); }
    private void enqueue(CurrentUser user,PipelineResponse run) {
        try { executor.submit(() -> {
            var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    user,null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + user.role().name()))));
            org.springframework.security.core.context.SecurityContextHolder.setContext(context);
            try { worker.run(user,run.id()); }
            finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        }); }
        catch (BusinessException e) {
            store.fail(user,run.id(),run.currentStep(),e.getMessage());
            throw e;
        }
    }
}
