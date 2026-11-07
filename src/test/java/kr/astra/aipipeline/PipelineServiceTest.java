package kr.astra.aipipeline;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import kr.astra.aipipeline.dto.PipelineResponse;
import kr.astra.aipipeline.entity.*;
import kr.astra.aipipeline.service.*;
import kr.astra.global.security.CurrentUser;
import kr.astra.llm.client.AiTaskExecutor;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
class PipelineServiceTest {
    @Test void workerHasAuthenticatedPrincipalForExistingBeBContractsAndClearsItAfterwards() {
        var store=mock(PipelineStore.class);
        var worker=mock(PipelineWorker.class);
        var executor=mock(AiTaskExecutor.class);
        var service=new PipelineService(store,worker,executor);
        var user=new CurrentUser(1L,UserRole.COMPANY_USER,null);
        var run=new PipelineResponse(9L,PipelineStatus.PENDING,PipelineStep.ANALYZE,List.of(),null,null,null,null);
        when(store.create(user,null,"시험")).thenReturn(run);
        doAnswer(i -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(user);
            return null;
        }).when(worker).run(user,9L);
        when(executor.submit(any())).thenAnswer(i -> { ((Runnable)i.getArgument(0)).run(); return null; });
        service.start(user,null,"시험");
        verify(worker).run(user,9L);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
