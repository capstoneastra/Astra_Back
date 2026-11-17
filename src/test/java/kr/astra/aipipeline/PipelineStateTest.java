package kr.astra.aipipeline;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import kr.astra.aipipeline.entity.*;
import kr.astra.aipipeline.repository.PipelineRepository;
import kr.astra.aipipeline.service.PipelineStore;
import kr.astra.chat.service.ChatPersistence;
import kr.astra.global.exception.*;
import kr.astra.global.security.CurrentUser;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.*;
class PipelineStateTest {
    final CurrentUser user=new CurrentUser(1L,UserRole.COMPANY_USER,null);
    final PipelineRepository repo=mock(PipelineRepository.class);
    final ChatPersistence chats=mock(ChatPersistence.class);
    final PipelineStore store=new PipelineStore(repo,chats);
    PipelineRun run;
    @BeforeEach void setup() {
        run=new PipelineRun(1L,null,"진동시험");
        when(repo.lockById(9L)).thenReturn(Optional.of(run));
        when(repo.findById(9L)).thenReturn(Optional.of(run));
    }
    @Test void waitingResumeKeepsRequirementAndDoesNotReenterCompletedSteps() {
        store.advance(user,9L,PipelineStep.ANALYZE,r -> r.analyzed(7L,false));
        assertThat(run.getStatus()).isEqualTo(PipelineStatus.WAITING_USER);
        store.resume(user,9L);
        assertThat(run.getRequirementId()).isEqualTo(7L);
        store.advance(user,9L,PipelineStep.ANALYZE,r -> r.analyzed(r.getRequirementId(),true));
        AtomicInteger writes=new AtomicInteger();
        store.advance(user,9L,PipelineStep.SEARCH,r -> { writes.incrementAndGet(); r.searched(2); });
        store.advance(user,9L,PipelineStep.SEARCH,r -> { writes.incrementAndGet(); r.searched(2); });
        assertThat(writes).hasValue(1);
        assertThat(run.getCurrentStep()).isEqualTo(PipelineStep.MATCH);
    }
    @Test void cancelPreventsLateWritesAndFailureOverwrite() {
        store.cancel(user,9L);
        AtomicInteger sideEffects=new AtomicInteger();
        store.advance(user,9L,PipelineStep.ANALYZE,r -> sideEffects.incrementAndGet());
        store.fail(user,9L,PipelineStep.ANALYZE,"late failure");
        assertThat(sideEffects).hasValue(0);
        assertThat(run.getStatus()).isEqualTo(PipelineStatus.CANCELED);
        assertThat(store.running(user,9L,PipelineStep.ANALYZE)).isFalse();
        assertThatThrownBy(() -> store.resume(user,9L)).isInstanceOf(BusinessException.class);
        store.cancel(user,9L); // idempotent cancellation
    }
    @Test void confirmedDraftSucceedsOnceAndCannotRepeatCreation() {
        run.analyzed(7L,true); run.searched(2); run.matched(3L);
        AtomicInteger drafts=new AtomicInteger();
        store.advance(user,9L,PipelineStep.DRAFT,r -> { drafts.incrementAndGet(); r.drafted(15L); });
        store.advance(user,9L,PipelineStep.DRAFT,r -> drafts.incrementAndGet());
        assertThat(drafts).hasValue(1);
        assertThat(run.getDraftRequestId()).isEqualTo(15L);
        assertThat(run.getStatus()).isEqualTo(PipelineStatus.SUCCEEDED);
        assertThat(run.getCurrentStep()).isEqualTo(PipelineStep.DONE);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.response().steps()).allSatisfy(s -> assertThat(s.status()).isEqualTo("DONE"));
        assertThatThrownBy(() -> store.cancel(user,9L)).isInstanceOf(BusinessException.class);
    }
    @Test void otherOwnersDeniedWhateverTheirRoleAndAdminAllowed() {
        assertThatThrownBy(() -> store.get(new CurrentUser(2L,UserRole.COMPANY_USER,null),9L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> store.get(new CurrentUser(2L,UserRole.INSTITUTION_MANAGER,3L),9L)).isInstanceOf(BusinessException.class);
        assertThat(store.get(new CurrentUser(99L,UserRole.ADMIN,null),9L)).isNotNull();
    }
    @Test void ownerMayUseOwnRunWhateverTheRole() {
        // 기관·대학 회원도 의뢰자로서 자기 자동 추진 실행을 다룰 수 있다 (역할이 아니라 본인 여부로 판단)
        assertThat(store.get(new CurrentUser(user.userId(),UserRole.INSTITUTION_MANAGER,3L),9L)).isNotNull();
    }
    @Test void startValidatesExclusiveInputsAndSessionAccessBeforeSaving() {
        assertThatThrownBy(() -> store.create(user,null,null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> store.create(user,3L,"input")).isInstanceOf(BusinessException.class);
        when(chats.ownerId(user,3L)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> store.create(user,3L,null)).isInstanceOf(BusinessException.class);
        verify(repo,never()).saveAndFlush(any());
    }
}
