package kr.astra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import kr.astra.global.exception.DbConstraints;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 실제 PostgreSQL 16(Testcontainers)에 Flyway V1·V2 를 적용한 뒤
 * 초기 데이터와 설계상 중요한 DB 제약조건이 동작하는지 검증합니다.
 * Docker 가 켜져 있어야 실행됩니다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    long equipmentId;       // ASTRA-CAND-0048 진동시험기
    long institutionId;     // 대전테크노파크 우주·양자산업센터
    long companyUserId;

    @BeforeAll
    void setUpFixtures() {
        equipmentId = jdbc.queryForObject("SELECT id FROM equipment WHERE external_id = 'ASTRA-CAND-0048'", Long.class);
        institutionId = jdbc.queryForObject("SELECT institution_id FROM equipment WHERE id = ?", Long.class, equipmentId);
        long companyId = jdbc.queryForObject(
                "INSERT INTO organizations (org_type, name) VALUES ('COMPANY', '테스트 기업') RETURNING id", Long.class);
        companyUserId = jdbc.queryForObject("""
                INSERT INTO users (organization_id, email, password_hash, name, role)
                VALUES (?, 'company@test.kr', 'x', '테스트 기업 사용자', 'COMPANY_USER') RETURNING id
                """, Long.class, companyId);
    }

    // ------------------------------------------------------------ 초기 데이터 (V2__seed.sql)

    @Test
    void 초기_데이터가_모두_들어간다() {
        assertThat(count("organizations WHERE org_type = 'INSTITUTION'")).isEqualTo(9);
        assertThat(count("equipment")).isEqualTo(71);
        assertThat(count("equipment_specs")).isEqualTo(117);
        assertThat(count("test_types")).isEqualTo(15);
        assertThat(count("test_condition_rules")).isEqualTo(32);
        assertThat(count("equipment_test_types")).isEqualTo(48);
        assertThat(count("simulation_models")).isEqualTo(20);
        assertThat(count("simulation_presets")).isEqualTo(71);
    }

    @Test
    void 모든_장비가_기관에_연결되고_시연용_사양은_MOCK_으로_표시된다() {
        assertThat(count("equipment WHERE institution_id IS NULL")).isZero();
        assertThat(count("equipment_specs WHERE source_type = 'MOCK' AND usage_scope <> 'DEMO_ONLY'")).isZero();
    }

    @Test
    void 조건_규칙의_사양_키는_모두_실제_장비_사양에_존재한다() {
        List<String> missing = jdbc.queryForList("""
                SELECT r.test_type_code || '.' || r.condition_key
                FROM test_condition_rules r
                WHERE r.spec_key IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM equipment_specs s WHERE s.spec_key = r.spec_key)
                """, String.class);
        assertThat(missing).isEmpty();
    }

    @Test
    void 시험유형마다_필수_조건이_하나_이상_있다() {
        assertThat(count("test_types t WHERE NOT EXISTS "
                + "(SELECT 1 FROM test_condition_rules r WHERE r.test_type_code = t.code AND r.is_required)")).isZero();
    }

    // ------------------------------------------------------------ 중복 예약 방지 (api-spec §7)

    @Test
    void 같은_장비의_확정_예약끼리_시간이_겹치면_거부된다() {
        long r1 = newRequest();
        long r2 = newRequest();
        reserve(r1, "2026-12-01 10:00+09", "2026-12-01 12:00+09", "CONFIRMED");

        assertThatThrownBy(() -> reserve(r2, "2026-12-01 11:00+09", "2026-12-01 13:00+09", "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    assertThat(DbConstraints.sqlState(e)).isEqualTo(DbConstraints.EXCLUSION_VIOLATION);
                    assertThat(DbConstraints.violates(e, DbConstraints.RESERVATION_NO_OVERLAP)).isTrue();
                });
    }

    @Test
    void 끝과_시작이_맞닿는_예약과_제안_중인_예약은_허용된다() {
        long r1 = newRequest();
        long r2 = newRequest();
        long r3 = newRequest();
        reserve(r1, "2026-12-02 10:00+09", "2026-12-02 12:00+09", "CONFIRMED");
        reserve(r2, "2026-12-02 12:00+09", "2026-12-02 14:00+09", "CONFIRMED");   // 맞닿음: 허용
        reserve(r3, "2026-12-02 11:00+09", "2026-12-02 13:00+09", "PROPOSED");    // 제안 중: 허용
        assertThat(count("reservations WHERE request_id IN (" + r1 + "," + r2 + "," + r3 + ")")).isEqualTo(3);
    }

    @Test
    void 의뢰_하나에_확정_예약은_하나만_가능하다() {
        long r1 = newRequest();
        reserve(r1, "2026-12-03 10:00+09", "2026-12-03 12:00+09", "CONFIRMED");

        assertThatThrownBy(() -> reserve(r1, "2026-12-04 10:00+09", "2026-12-04 12:00+09", "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    assertThat(DbConstraints.sqlState(e)).isEqualTo(DbConstraints.UNIQUE_VIOLATION);
                    assertThat(DbConstraints.violates(e, DbConstraints.RESERVATION_ONE_CONFIRMED)).isTrue();
                });
    }

    // ------------------------------------------------------------ 보완 요청 (api-spec §6)

    @Test
    void 답변_대기_중인_보완_요청은_의뢰당_하나만_가능하다() {
        long r1 = newRequest();
        long first = supplement(r1, "OPEN");

        assertThatThrownBy(() -> supplement(r1, "OPEN"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(DbConstraints.violates(e, DbConstraints.SUPPLEMENT_ONE_OPEN)).isTrue());

        // 첫 요청에 답변하면 새 보완 요청을 열 수 있다
        jdbc.update("UPDATE supplement_requests SET status = 'RESPONDED' WHERE id = ?", first);
        supplement(r1, "OPEN");
    }

    // ------------------------------------------------------------ helpers

    private long count(String fromWhere) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromWhere, Long.class);
    }

    private long newRequest() {
        return jdbc.queryForObject("""
                INSERT INTO test_requests (request_no, requester_id, institution_id, equipment_id, title, status)
                VALUES ('REQ-TEST-' || nextval('test_requests_id_seq'), ?, ?, ?, '테스트 의뢰', 'SCHEDULING')
                RETURNING id
                """, Long.class, companyUserId, institutionId, equipmentId);
    }

    private void reserve(long requestId, String startAt, String endAt, String status) {
        jdbc.update("""
                INSERT INTO reservations (request_id, equipment_id, start_at, end_at, status, proposed_by)
                VALUES (?, ?, ?::timestamptz, ?::timestamptz, ?, ?)
                """, requestId, equipmentId, startAt, endAt, status, companyUserId);
    }

    private long supplement(long requestId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO supplement_requests (request_id, based_revision_no, requested_by, message, status)
                VALUES (?, 1, ?, '고정구 질량을 알려 주세요.', ?) RETURNING id
                """, Long.class, requestId, companyUserId, status);
    }
}
