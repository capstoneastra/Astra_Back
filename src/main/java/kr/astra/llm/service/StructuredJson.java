package kr.astra.llm.service;
import java.util.*;
import kr.astra.global.exception.*;
import tools.jackson.databind.*;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
/** JSON types and allowed fields are checked before DTO binding (no string-to-number coercion). */
@Component @RequiredArgsConstructor
public class StructuredJson {
    private final ObjectMapper mapper;
    public JsonNode parse(String json) {
        try {
            if (json == null || json.length() > 100000) throw invalid();
            JsonNode node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).readTree(json);
            if (node == null || !node.isObject()) throw invalid();
            return node;
        } catch (Exception e) { throw invalid(); }
    }
    public void fields(JsonNode n, String... allowed) {
        if (!n.isObject() || !Set.of(allowed).containsAll(n.propertyNames())) throw invalid();
    }
    public String text(JsonNode n, String field, boolean required, int max) {
        JsonNode value = n.get(field);
        if (value == null || value.isNull()) { if (required) throw invalid(); return null; }
        if (!value.isString() || value.asString().length() > max || (required && value.asString().isBlank())) throw invalid();
        return value.asString();
    }
    public java.math.BigDecimal number(JsonNode n, String field) {
        JsonNode value = n.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw invalid();
        return value.decimalValue();
    }
    public String write(Object value) { return mapper.writeValueAsString(value); }
    public static Map<String,Object> object(Map<String,Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }
    public static Map<String,Object> nullable(String type) { return Map.of("type", List.of(type, "null")); }
    public static BusinessException invalid() { return new BusinessException(ErrorCode.AI_UPSTREAM_ERROR, "AI 응답 형식 또는 근거를 확인할 수 없습니다.", null); }
}
