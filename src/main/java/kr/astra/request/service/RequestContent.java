package kr.astra.request.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.request.dto.RequestResponses.FieldChange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 의뢰서 본문 content (api-spec §5 스키마) 다루기. DB 에는 JSON 문자열로 저장합니다.
 * <pre>
 * { "applicant": {...}, "test": {...}, "sample": { "name", "quantity", "readyDate" },
 *   "conditions": [ { "key": "sample_mass", "value": 15, "unit": "kg", ... } ],
 *   "schedule": { "desiredStartDate": "2026-11-20", "desiredEndDate": "2026-11-21" },
 *   "equipment": {...}, "notes": "..." }
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class RequestContent {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };
    /** 본문 JSON 최대 크기 (문자 수). 첨부 파일은 attachments 로 따로 */
    private static final int MAX_LENGTH = 200_000;

    private final JsonMapper jsonMapper;

    public String write(Map<String, Object> content) {
        String json = jsonMapper.writeValueAsString(content == null ? Map.of() : content);
        if (json.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "의뢰서 본문이 너무 큽니다.",
                    Map.of("field", "content"));
        }
        return json;
    }

    public Map<String, Object> read(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        return jsonMapper.readValue(json, MAP);
    }

    /** 객체로 묶여야 하는 항목(applicant, test, sample, schedule, equipment)과 conditions 배열 형식만 검사 */
    public void validate(Map<String, Object> content) {
        if (content == null) {
            throw invalid("content", "의뢰서 본문이 없습니다.");
        }
        for (String key : List.of("applicant", "test", "sample", "schedule", "equipment")) {
            Object v = content.get(key);
            if (v != null && !(v instanceof Map)) {
                throw invalid("content." + key, key + " 은(는) 객체여야 합니다.");
            }
        }
        Object conditions = content.get("conditions");
        Object services = content.get("serviceRequirements");
        if (services != null) {
            if (!(services instanceof Map<?,?> m)) throw invalid("content.serviceRequirements", "객체여야 합니다.");
            for (var entry : m.entrySet()) {
                if (!Set.of("accreditedRequired", "operatorSupportRequested", "securityOrExportControlled").contains(entry.getKey())
                        || entry.getValue() != null && !(entry.getValue() instanceof Boolean))
                    throw invalid("content.serviceRequirements", "허용된 항목은 boolean 또는 null이어야 합니다.");
            }
        }
        if (conditions != null) {
            if (!(conditions instanceof List<?> list)) {
                throw invalid("content.conditions", "conditions 는 배열이어야 합니다.");
            }
            for (Object c : list) {
                if (!(c instanceof Map<?, ?> m) || !(m.get("key") instanceof String k) || k.isBlank()) {
                    throw invalid("content.conditions", "조건마다 key(조건 키)가 있어야 합니다.");
                }
            }
        }
        Schedule s = schedule(content);
        if (s.start() != null && s.end() != null && s.end().isBefore(s.start())) {
            throw invalid("content.schedule", "희망 종료일이 시작일보다 빠릅니다.");
        }
    }

    public record Schedule(LocalDate start, LocalDate end) {
    }

    /** content.schedule 의 희망 일정 (없거나 비어 있으면 null) */
    public Schedule schedule(Map<String, Object> content) {
        if (content == null || !(content.get("schedule") instanceof Map<?, ?> schedule)) {
            return new Schedule(null, null);
        }
        return new Schedule(date(schedule.get("desiredStartDate"), "desiredStartDate"),
                date(schedule.get("desiredEndDate"), "desiredEndDate"));
    }

    private static LocalDate date(Object value, String field) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            return null;
        }
        try {
            return LocalDate.parse(value.toString());
        } catch (DateTimeParseException e) {
            throw invalid("content.schedule." + field, "날짜는 2026-11-20 형식이어야 합니다.");
        }
    }

    /**
     * 보완 항목 fieldKey 가 본문에 채워져 있는지.
     * fieldKey 는 조건 키(fixture_mass) 또는 content 경로(sample.readyDate) — api-spec §6.
     */
    public boolean isFilled(Map<String, Object> content, String fieldKey) {
        if (fieldKey == null || fieldKey.isBlank()) {
            return true;
        }
        if (fieldKey.contains(".")) {
            Object cur = content;
            for (String part : fieldKey.split("\\.")) {
                if (!(cur instanceof Map<?, ?> m)) {
                    return false;
                }
                cur = m.get(part);
            }
            return notEmpty(cur);
        }
        if (content.get("conditions") instanceof List<?> list) {
            for (Object c : list) {
                if (c instanceof Map<?, ?> m && fieldKey.equals(m.get("key"))) {
                    if ("MISSING".equals(m.get("status"))) {
                        return false;
                    }
                    return notEmpty(m.get("value")) || notEmpty(m.get("min")) || notEmpty(m.get("max"))
                            || notEmpty(m.get("valueText"));
                }
            }
        }
        return notEmpty(content.get(fieldKey));
    }

    private static boolean notEmpty(Object v) {
        return v != null && !(v instanceof String s && s.isBlank());
    }

    /** 두 본문의 바뀐 값 목록 (조건은 key 로 맞춰 비교) */
    public List<FieldChange> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> a = flatten(before);
        Map<String, Object> b = flatten(after);
        Set<String> paths = new LinkedHashSet<>(a.keySet());
        paths.addAll(b.keySet());
        List<FieldChange> changes = new ArrayList<>();
        for (String path : paths) {
            Object x = a.get(path);
            Object y = b.get(path);
            if (!sameValue(x, y)) {
                changes.add(new FieldChange(path, x, y));
            }
        }
        return changes;
    }

    private static boolean sameValue(Object x, Object y) {
        if (x instanceof Number n1 && y instanceof Number n2) {
            return new java.math.BigDecimal(n1.toString()).compareTo(new java.math.BigDecimal(n2.toString())) == 0;
        }
        return Objects.equals(x, y);
    }

    private static Map<String, Object> flatten(Map<String, Object> content) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (content != null) {
            flatten("", content, out);
        }
        return out;
    }

    private static void flatten(String prefix, Object value, Map<String, Object> out) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                flatten(prefix + e.getKey() + ".", e.getValue(), out);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                String id = item instanceof Map<?, ?> m && m.get("key") instanceof String k ? k : String.valueOf(i);
                flatten(prefix + id + ".", item, out);
            }
        } else {
            out.put(prefix.isEmpty() ? "" : prefix.substring(0, prefix.length() - 1), value);
        }
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message, Map.of("field", field));
    }
}
