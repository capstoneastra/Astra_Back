package kr.astra.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI: http://localhost:8080/swagger-ui.html
 * 오른쪽 위 Authorize 버튼에 로그인 응답의 accessToken 을 넣으면 인증이 필요한 API 를 호출할 수 있습니다.
 */
@Configuration
public class SwaggerConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI astraOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ASTRA API")
                        .version("v1")
                        .description("우주항공 시험장비 공동활용 플랫폼 API. 설계 문서: docs/api-spec.md"))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
