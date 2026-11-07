package kr.astra.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 우리 컨트롤러(kr.astra 패키지의 @RestController)에 /api/v1 을 자동으로 붙입니다.
 * 컨트롤러에는 api-spec.md 의 경로를 그대로 씁니다. 예) @RequestMapping("/equipment") → /api/v1/equipment
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    public static final String API_PREFIX = "/api/v1";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_PREFIX,
                type -> type.isAnnotationPresent(RestController.class)
                        && type.getPackageName().startsWith("kr.astra"));
    }
}
