package kr.astra;

import static kr.astra.TestApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
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
import tools.jackson.databind.JsonNode;

/** 관리자 조직 관리 (api-spec §12): 목록, 기관 직접 등록, 승인, 수정 */
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminOrganizationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    CapturingEmailSender mail;

    private TestApi api;
    private String admin;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, mail);
        long adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'admin@astra.kr'", Long.class);
        admin = jwtProvider.createAccessToken(adminId, UserRole.ADMIN, null);
    }

    @Test
    void 기관을_직접_등록하면_바로_승인되고_가입용_목록에_나온다() throws Exception {
        String name = "테스트 등록 기관 " + UUID.randomUUID();
        Res created = api.post("/api/v1/admin/organizations", """
                { "name": "%s", "region": "진주", "phone": "055-000-0009", "email": "lab@test.kr" }""".formatted(name),
                admin);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.data().path("orgType").asString()).isEqualTo("INSTITUTION");
        assertThat(created.data().path("isVerified").asBoolean()).isTrue();
        assertThat(api.get("/api/v1/auth/institutions", null).data().toString()).contains(name);

        Res duplicate = api.post("/api/v1/admin/organizations", "{ \"name\": \"%s\" }".formatted(name), admin);
        assertThat(duplicate.code()).isEqualTo("ORGANIZATION_ALREADY_EXISTS");
    }

    @Test
    void 대학도_직접_등록할_수_있고_기업_유형은_400() throws Exception {
        String name = "테스트 등록 대학 " + UUID.randomUUID();
        Res created = api.post("/api/v1/admin/organizations", """
                { "orgType": "UNIVERSITY", "name": "%s", "region": "진주" }""".formatted(name), admin);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.data().path("orgType").asString()).isEqualTo("UNIVERSITY");
        assertThat(created.data().path("isVerified").asBoolean()).isTrue();
        assertThat(api.get("/api/v1/auth/institutions", null).data().toString()).contains(name);
        assertThat(api.get("/api/v1/institutions/" + created.data().path("id").asLong(), admin)
                .data().path("orgType").asString()).isEqualTo("UNIVERSITY");

        Res company = api.post("/api/v1/admin/organizations",
                "{ \"orgType\": \"COMPANY\", \"name\": \"x\" }", admin);
        assertThat(company.code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void 미승인_조직을_목록에서_찾아_승인한다() throws Exception {
        String orgName = "테스트 신청 기관 " + UUID.randomUUID();
        api.signupInstitutionManager(uniqueEmail(), """
                "organization": { "name": "%s", "address": "대전", "phone": "042-000-0000" }""".formatted(orgName));
        long orgId = jdbc.queryForObject("SELECT id FROM organizations WHERE org_type = 'INSTITUTION' AND name = ?",
                Long.class, orgName);

        JsonNode pending = api.get("/api/v1/admin/organizations?orgType=INSTITUTION&verified=false&size=100", admin)
                .data().path("content");
        boolean found = false;
        for (JsonNode n : pending) {
            assertThat(n.path("isVerified").asBoolean()).isFalse();
            found |= n.path("id").asLong() == orgId;
        }
        assertThat(found).isTrue();

        Res verified = api.post("/api/v1/admin/organizations/" + orgId + "/verify", null, admin);
        assertThat(verified.status()).isEqualTo(200);
        assertThat(verified.data().path("isVerified").asBoolean()).isTrue();
        // 이미 승인됐어도 그대로 200
        assertThat(api.post("/api/v1/admin/organizations/" + orgId + "/verify", null, admin).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/admin/organizations/999999/verify", null, admin).code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void 조직_정보는_보낸_값만_고치고_이름이_겹치면_409() throws Exception {
        String name = "테스트 수정 기관 " + UUID.randomUUID();
        long id = api.post("/api/v1/admin/organizations", "{ \"name\": \"%s\", \"region\": \"대전\" }".formatted(name),
                admin).data().path("id").asLong();

        Res patched = api.send("PATCH", "/api/v1/admin/organizations/" + id,
                "{ \"homepageUrl\": \"https://lab.test.kr\" }", admin);
        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.data().path("homepageUrl").asString()).isEqualTo("https://lab.test.kr");
        assertThat(patched.data().path("region").asString()).isEqualTo("대전");

        assertThat(api.send("PATCH", "/api/v1/admin/organizations/" + id, "{ \"name\": \"다온신뢰성시험센터\" }", admin)
                .code()).isEqualTo("ORGANIZATION_ALREADY_EXISTS");
    }

    @Test
    void 관리자가_아니면_403_잘못된_정렬은_400() throws Exception {
        long companyId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'company@astra.kr'", Long.class);
        String company = jwtProvider.createAccessToken(companyId, UserRole.COMPANY_USER, null);
        assertThat(api.get("/api/v1/admin/organizations", company).code()).isEqualTo("FORBIDDEN");
        assertThat(api.post("/api/v1/admin/organizations", "{ \"name\": \"x\" }", company).code()).isEqualTo("FORBIDDEN");

        Res page = api.get("/api/v1/admin/organizations?orgType=INSTITUTION&verified=true&size=100", admin);
        assertThat(page.data().path("totalElements").asLong()).isGreaterThanOrEqualTo(9);
        assertThat(api.get("/api/v1/admin/organizations?sort=name", admin).code()).isEqualTo("VALIDATION_FAILED");
    }
}
