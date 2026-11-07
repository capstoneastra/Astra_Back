package kr.astra;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import kr.astra.TestMailConfiguration.CapturingEmailSender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트용 HTTP 호출 도우미 (java.net.http). 회원가입 시 이메일 인증까지 처리합니다.
 * 테스트 비밀번호는 테스트에서 가입시킨 계정 전용입니다.
 */
class TestApi {

    static final String PASSWORD = "test-password-1";

    private final int port;
    private final CapturingEmailSender mail;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = JsonMapper.builder().build();

    TestApi(int port, CapturingEmailSender mail) {
        this.port = port;
        this.mail = mail;
    }

    static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@test.kr";
    }

    /** 인증 코드 발송 → 메일로 받은 코드 확인 → 가입용 verificationToken */
    String verificationToken(String email) throws Exception {
        Res sent = post("/api/v1/auth/email-verifications", "{ \"email\": \"%s\" }".formatted(email), null);
        assertThat(sent.status).as(sent.body.toString()).isEqualTo(200);
        Res confirmed = post("/api/v1/auth/email-verifications/confirm",
                "{ \"email\": \"%s\", \"code\": \"%s\" }".formatted(email, mail.lastCode(email)), null);
        assertThat(confirmed.status).as(confirmed.body.toString()).isEqualTo(200);
        return confirmed.data().path("verificationToken").asString();
    }

    /** 이메일 인증 후 기업 가입 (가입 직후 PENDING) */
    Res signupCompany(String email, String companyName, String businessRegNo) throws Exception {
        return post("/api/v1/auth/signup", """
                { "email": "%s", "password": "%s", "name": "홍길동", "role": "COMPANY_USER",
                  "verificationToken": "%s",
                  "organization": { "name": "%s", "businessRegNo": "%s" } }
                """.formatted(email, PASSWORD, verificationToken(email), companyName, businessRegNo), null);
    }

    /** 이메일 인증 후 기관 담당자 가입. orgJson 은 "organizationId": 3 또는 "organization": {...} */
    Res signupInstitutionManager(String email, String orgJson) throws Exception {
        return post("/api/v1/auth/signup", """
                { "email": "%s", "password": "%s", "name": "김담당", "role": "INSTITUTION_MANAGER",
                  "verificationToken": "%s", %s }
                """.formatted(email, PASSWORD, verificationToken(email), orgJson), null);
    }

    Res login(String email) throws Exception {
        return post("/api/v1/auth/login",
                "{ \"email\": \"%s\", \"password\": \"%s\" }".formatted(email, PASSWORD), null);
    }

    Res get(String path, String accessToken) throws Exception {
        return send("GET", path, null, accessToken);
    }

    Res post(String path, String body, String accessToken) throws Exception {
        return send("POST", path, body, accessToken);
    }

    Res send(String method, String path, String body, String accessToken) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (accessToken != null) {
            req.header("Authorization", "Bearer " + accessToken);
        }
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(res.statusCode(), json.readTree(res.body()));
    }

    record Res(int status, JsonNode body) {
        JsonNode data() {
            return body.path("data");
        }

        String code() {
            return body.path("error").path("code").asString();
        }

        JsonNode details() {
            return body.path("error").path("details");
        }
    }
}
