package kr.astra.aipipeline.service;

import kr.astra.global.exception.BusinessException;
import kr.astra.global.security.CurrentUser;
import kr.astra.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Restarts only persisted PENDING/RUNNING work; committed checkpoints are never replayed. */
@Component @RequiredArgsConstructor
public class PipelineRecovery {
    private final PipelineStore store;
    private final PipelineService service;
    private final UserService users;

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        long cursor = 0;
        while (true) {
            var batch = store.recoverableAfter(cursor);
            if (batch.isEmpty()) return;
            for (var run : batch) {
                cursor = run.id();
                CurrentUser actor;
                try {
                    var account = users.getActiveAccount(run.userId());
                    actor = new CurrentUser(account.id(), account.role(), account.organizationId());
                } catch (BusinessException e) {
                    store.rejectRecovery(run.id());
                    continue;
                }
                try { service.recover(actor,run.id()); }
                catch (BusinessException ignored) { /* enqueue already records a bounded-queue rejection */ }
            }
        }
    }
}
