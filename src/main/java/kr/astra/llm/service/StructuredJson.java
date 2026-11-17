package kr.astra.llm.service;
import java.util.*;
import kr.astra.global.exception.*;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.*;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
/** JSON types and allowed fields are checked before DTO binding (no string-to-number coercion). */
@Slf4j @Component @RequiredArgsConstructor
public class StructuredJson {
    private final ObjectMapper mapper;
    public JsonNode parse(String json) {
        if (json == null || json.length() > 100000) throw invalid(json == null ? "응답 없음" : "응답이 너무 김");
        try {
            JsonNode node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).readTree(json);
            if (node == null || !node.isObject()) throw invalid("JSON 객체가 아님");
            return node;
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw invalid("JSON 파싱 실패: " + e.getClass().getSimpleName()); }
    }
    public void fields(JsonNode n, String... allowed) {
        if (!n.isObject()) throw invalid("객체가 아님");
        if (!Set.of(allowed).containsAll(n.propertyNames())) throw invalid("허용되지 않은 필드");
    }
    public String text(JsonNode n, String field, boolean required, int max) {
        JsonNode value = n.get(field);
        if (value == null || value.isNull()) { if (required) throw invalid(field + " 누락"); return null; }
        if (!value.isString() || value.asString().length() > max || (required && value.asString().isBlank()))
            throw invalid(field + " 형식 또는 길이 오류");
        return value.asString();
    }
    public java.math.BigDecimal number(JsonNode n, String field) {
        JsonNode value = n.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw invalid(field + " 가 숫자가 아님");
        return value.decimalValue();
    }
    public String write(Object value) { return mapper.writeValueAsString(value); }
    public static Map<String,Object> object(Map<String,Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }
    public static Map<String,Object> nullable(String type) { return Map.of("type", List.of(type, "null")); }
    public static BusinessException invalid() { return invalid(null); }
    /** 진단용으로 사유와 호출 위치를 남깁니다. 사용자 입력·AI 원문·키는 로그에 넣지 않습니다. */
    public static BusinessException invalid(String reason) {
        String caller = StackWalker.getInstance().walk(frames -> frames
                .filter(f -> !f.getClassName().equals(StructuredJson.class.getName()))
                .findFirst().map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1)
                        + "." + f.getMethodName() + ":" + f.getLineNumber()).orElse("?"));
        log.warn("AI 응답 검증 실패 ({}) at {}", reason == null ? "형식 오류" : reason, caller);
        return new BusinessException(ErrorCode.AI_UPSTREAM_ERROR, "AI 응답 형식 또는 근거를 확인할 수 없습니다.", null);
    }
}
