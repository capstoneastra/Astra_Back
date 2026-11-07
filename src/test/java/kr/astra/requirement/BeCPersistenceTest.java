package kr.astra.requirement;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import kr.astra.aipipeline.entity.*;
import kr.astra.aipipeline.service.PipelineStore;
import kr.astra.chat.service.ChatPersistence;
import kr.astra.global.security.CurrentUser;
import kr.astra.matching.service.MatchingService;
import kr.astra.requirement.dto.*;
import kr.astra.requirement.entity.*;
import kr.astra.requirement.service.RequirementApplicationService;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real Flyway/JPA checks. Requires Docker; deliberately not disabled when Docker is absent. */
@SpringBootTest
@Import(BeCPersistenceTest.Database.class)
class BeCPersistenceTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean @ServiceConnection PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:16"));
        }
    }
    @Autowired RequirementApplicationService requirements;
    @Autowired ChatPersistence chats;
    @Autowired MatchingService matching;
    @Autowired PipelineStore pipelines;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    kr.astra.llm.service.MatchExplanationService explanations;
    CurrentUser owner;
    @BeforeEach void owner() {
        org.mockito.Mockito.when(explanations.explain(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            List<kr.astra.llm.service.MatchExplanationService.Input> inputs=invocation.getArgument(0);
            Map<Long,String> result=new HashMap<>(); inputs.forEach(i -> result.put(i.equipmentId(),"테스트 설명"));
            return result;
        });
        Long id=jdbc.queryForObject("select id from users where email = 'company@astra.demo'",Long.class);
        owner=new CurrentUser(id,UserRole.COMPANY_USER,null);
    }
    ConditionInput number(String key,String value,String unit) {
        return new ConditionInput(key,new BigDecimal(value),null,null,null,unit,ConditionStatus.CONFIRMED,null,null);
    }
    RequirementInput input() {
        return new RequirementInput("VIBRATION","검증용 조건",null,"시료",1,List.of(
                number("sample_mass","15","kg"),number("fixture_mass","2","kg"),
                new ConditionInput("frequency",null,new BigDecimal("20"),new BigDecimal("2000"),null,"Hz",ConditionStatus.CONFIRMED,null,null)));
    }
    @Test void schemaAndJpaRoundTripPreserveConditionsSnapshotChecksAndHistory() {
        var requirement=requirements.create(owner,input());
        var match=matching.run(owner,requirement.id(),null,5);
        assertThat(match.results()).isNotEmpty();
        assertThat(matching.get(owner,match.id()).results().getFirst().checks()).hasSize(3);
        assertThat(jdbc.queryForObject("select requirement_snapshot ->> 'testTypeCode' from match_runs where id = ?",String.class,match.id()))
                .isEqualTo("VIBRATION");
        assertThat(matching.history(owner,requirement.id(),org.springframework.data.domain.PageRequest.of(0,20)).content()).hasSize(1);
        assertThat(requirements.confirm(owner,requirement.id()).status()).isEqualTo(RequirementStatus.CONFIRMED);
    }
    @Test void conversationMetadataAndSessionLinkPersist() {
        var session=chats.create(owner,"상담");
        var turn=chats.begin(owner,session.id(),"시료 15kg");
        chats.complete(owner,turn,"고정구 질량을 알려 주세요.",Map.of("model","test-only"));
        assertThat(chats.messages(owner,session.id(),org.springframework.data.domain.PageRequest.of(0,20)).content()).hasSize(2);
        var requirement=requirements.createForSession(owner,owner.userId(),session.id(),input());
        assertThat(requirements.get(owner,requirement.id()).chatSessionId()).isEqualTo(session.id());
    }
    @Test void concurrentCheckpointCommitsSideEffectOnceAndCancelWinsOverLateCallback() throws Exception {
        var run=pipelines.create(owner,null,"진동시험");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        List<Future<?>> futures=new ArrayList<>();
        String marker="concurrent checkpoint " + run.id();
        try {
            for (int i=0;i<2;i++) futures.add(pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                pipelines.advance(owner,run.id(),PipelineStep.ANALYZE,r -> {
                    var req=requirements.create(owner,new RequirementInput("VIBRATION",marker,null,null,null,List.of()));
                    r.analyzed(req.id(),false);
                });
            }));
            start.countDown();
            for (var future:futures) future.get(10,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("select count(*) from test_requirements where title = ?",Long.class,marker)).isEqualTo(1);
        pipelines.cancel(owner,run.id());
        pipelines.advance(owner,run.id(),PipelineStep.ANALYZE,r -> { throw new AssertionError("Canceled callback executed"); });
        assertThat(pipelines.get(owner,run.id()).status()).isEqualTo(PipelineStatus.CANCELED);
    }
    @Test void failedCheckpointRollsBackDomainWriteAsWellAsRunUpdate() {
        var run=pipelines.create(owner,null,"진동시험");
        String marker="rolled back checkpoint " + run.id();
        assertThatThrownBy(() -> pipelines.advance(owner,run.id(),PipelineStep.ANALYZE,r -> {
            requirements.create(owner,new RequirementInput("VIBRATION",marker,null,null,null,List.of()));
            throw new IllegalStateException("test rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from test_requirements where title = ?",Long.class,marker)).isZero();
        assertThat(pipelines.get(owner,run.id()).requirementId()).isNull();
    }
}
