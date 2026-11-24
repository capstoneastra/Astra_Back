package kr.astra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kr.astra.user.dto.UserUpdateRequest;
import kr.astra.user.entity.UserStatus;
import kr.astra.user.repository.UserRepository;
import kr.astra.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * users 행 동시 수정: 로그인 기록·내 정보 수정이 관리자 정지를 덮어쓰지 않는지 확인합니다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UserConcurrencyTest {

    private static final String PASSWORD = "test-password-1";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserService userService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void 로그인_기록이_그_사이에_들어온_정지를_되돌리지_않는다() throws Exception {
        String email = "login-race-" + UUID.randomUUID() + "@test.kr";
        long userId = activeUser(email);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<Integer> suspend = new CompletableFuture<>();

        tx.executeWithoutResult(status -> {
            userService.authenticate(email, PASSWORD); // ACTIVE 로 읽고 마지막 로그인 기록
            // 커밋 전에 관리자가 정지 (다른 트랜잭션)
            CompletableFuture.runAsync(() -> suspend.complete(
                    jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", userId)));
            try {
                suspend.get(500, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // 로그인 쪽이 행 잠금을 잡고 있으면 커밋 후에 정지가 반영됨
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        suspend.get(5, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("SELECT last_login_at IS NOT NULL FROM users WHERE id = ?",
                Boolean.class, userId)).isTrue();
    }

    @Test
    void 내_정보_수정_중에_정지되면_덮어쓰지_않고_버전_충돌() throws Exception {
        String email = "update-race-" + UUID.randomUUID() + "@test.kr";
        long userId = activeUser(email);
        long adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'admin@astra.kr'", Long.class);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            userRepository.findById(userId).orElseThrow(); // ACTIVE 상태로 읽어 둠
            // 그 사이 관리자가 정지 (별도 트랜잭션에서 커밋)
            CompletableFuture.runAsync(() -> userService.changeStatus(userId, adminId, UserStatus.SUSPENDED)).join();
            userService.updateMe(userId, new UserUpdateRequest("새이름", null));
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(jdbc.queryForMap("SELECT status, name FROM users WHERE id = ?", userId))
                .containsEntry("status", "SUSPENDED").containsEntry("name", "테스트");
    }

    private long activeUser(String email) {
        long orgId = jdbc.queryForObject("INSERT INTO organizations (org_type, name) VALUES ('COMPANY', ?) RETURNING id",
                Long.class, "동시성 기업 " + UUID.randomUUID());
        return jdbc.queryForObject("""
                INSERT INTO users (organization_id, email, password_hash, name, role, status)
                VALUES (?, ?, ?, '테스트', 'COMPANY_USER', 'ACTIVE') RETURNING id
                """, Long.class, orgId, email, passwordEncoder.encode(PASSWORD));
    }
}
