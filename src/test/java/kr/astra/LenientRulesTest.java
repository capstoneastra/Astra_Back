package kr.astra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import kr.astra.TestApi.Res;
import kr.astra.TestMailConfiguration.CapturingEmailSender;
import kr.astra.global.security.JwtProvider;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * astra.rules.strict=false (프론트가 새 필수 칸을 만들기 전 연동용).
 * 회의 이후 추가된 필수 검사 3가지가 꺼져서 예전 화면으로도 가입·제출·완료가 됨.
 */
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "astra.rules.strict=false")
class LenientRulesTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    CapturingEmailSender mail;

    private TestApi api;
    private String company;
    private String manager1;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, mail);
        company = token("company@astra.kr", UserRole.COMPANY_USER);
        manager1 = token("lab1@astra.kr", UserRole.INSTITUTION_MANAGER);
    }

    @Test
    void 국가연구자번호_없이도_기관_담당자가_가입된다_형식은_그대로_검사() throws Exception {
        long orgId = jdbc.queryForObject("SELECT id FROM organizations WHERE name = '가람재료시험연구원'", Long.class);
        Res ok = api.signupInstitutionManager(TestApi.uniqueEmail(), "\"organizationId\": " + orgId, "");
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(201);
        Res wrong = api.signupInstitutionManager(TestApi.uniqueEmail(), "\"organizationId\": " + orgId,
                "\"researcherNo\": \"12-34\"");
        assertThat(wrong.code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void 필수_칸_없이_제출되고_결과_없이_완료된다() throws Exception {
        long id = api.post("/api/v1/test-requests", "{ \"equipmentId\": 48 }", company).data().path("id").asLong();
        Res submitted = api.post("/api/v1/test-requests/" + id + "/submit", null, company);
        assertThat(submitted.status()).as(submitted.body().toString()).isEqualTo(200);

        assertThat(api.post("/api/v1/test-requests/" + id + "/review/start", null, manager1).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/test-requests/" + id + "/accept", null, manager1).status()).isEqualTo(200);
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(60).truncatedTo(ChronoUnit.HOURS);
        long resv = api.post("/api/v1/test-requests/" + id + "/reservations",
                "{ \"startAt\": \"%s\", \"endAt\": \"%s\" }".formatted(start, start.plusHours(2)), company)
                .data().path("reservation").path("id").asLong();
        assertThat(api.post("/api/v1/reservations/" + resv + "/confirm", null, manager1).status()).isEqualTo(200);

        Res completed = api.post("/api/v1/test-requests/" + id + "/complete", null, manager1);
        assertThat(completed.status()).as(completed.body().toString()).isEqualTo(200);
        assertThat(completed.data().path("status").asString()).isEqualTo("COMPLETED");
    }

    private String token(String email, UserRole role) {
        var row = jdbc.queryForMap("SELECT id, organization_id FROM users WHERE email = ?", email);
        return jwtProvider.createAccessToken(((Number) row.get("id")).longValue(), role,
                row.get("organization_id") == null ? null : ((Number) row.get("organization_id")).longValue());
    }
}
