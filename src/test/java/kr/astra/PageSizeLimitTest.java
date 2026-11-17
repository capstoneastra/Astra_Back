package kr.astra;

import static org.assertj.core.api.Assertions.assertThat;

import kr.astra.global.security.JwtProvider;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** 목록 API 의 page size 는 최대 100 (spring.data.web.pageable.max-page-size). 큰 값을 보내도 100 으로 잘림 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PageSizeLimitTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JwtProvider jwtProvider;

    @Test
    void 페이지_크기는_100을_넘지_않는다() throws Exception {
        TestApi api = new TestApi(port, null);
        String token = jwtProvider.createAccessToken(1L, UserRole.COMPANY_USER, null);

        var res = api.get("/api/v1/equipment?size=2000", token);

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.data().path("size").asInt()).isEqualTo(100);
        assertThat(res.data().path("content").size()).isLessThanOrEqualTo(100);
    }
}
