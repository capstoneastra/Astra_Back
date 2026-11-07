package kr.astra.equipment.repository;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.astra.equipment.dto.EquipmentSearchCondition;
import kr.astra.equipment.dto.InstitutionTestTypeStat;
import kr.astra.equipment.entity.AccessScope;
import kr.astra.equipment.entity.EquipmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 장비 검색·집계용 SQL. 조건이 많고 pg_trgm·배열(keywords) 같은 PostgreSQL 기능을 써서 JDBC 로 작성합니다.
 * 결과는 id·숫자만 돌려주고, 장비 내용은 EquipmentRepository(JPA)로 다시 읽습니다.
 * 모든 쿼리는 활성 장비(is_active)만 대상으로 합니다.
 */
@Repository
@RequiredArgsConstructor
public class EquipmentJdbcRepository {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * GET /equipment 검색. q 가 있으면 이름 유사도(pg_trgm similarity) 높은 순, 그다음 orderBy.
     *
     * @param orderBy 서비스에서 허용 목록으로 검증한 ORDER BY 절 (예: "e.external_id ASC")
     */
    public Page<Long> search(EquipmentSearchCondition c, Pageable pageable, String orderBy) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(c, params);
        Long total = jdbc.queryForObject("SELECT count(*) FROM equipment e WHERE " + where, params, Long.class);
        String order = StringUtils.hasText(c.q()) ? "similarity(e.name, :qRaw) DESC, " + orderBy : orderBy;
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<Long> ids = jdbc.queryForList("SELECT e.id FROM equipment e WHERE " + where
                + " ORDER BY " + order + " LIMIT :limit OFFSET :offset", params, Long.class);
        return new PageImpl<>(ids, pageable, total == null ? 0 : total);
    }

    private static String where(EquipmentSearchCondition c, MapSqlParameterSource params) {
        List<String> and = new ArrayList<>();
        and.add("e.is_active");
        if (StringUtils.hasText(c.q())) {
            String q = c.q().trim();
            params.addValue("qRaw", q).addValue("q", "%" + escapeLike(q) + "%");
            // 장비명·모델·제조사, 또는 이 장비의 시험유형(이름·코드·키워드). 키워드는 "진동시험기"처럼 검색어 안에 들어 있어도 일치
            and.add("""
                    (e.name ILIKE :q OR e.model ILIKE :q OR e.manufacturer ILIKE :q
                     OR EXISTS (SELECT 1 FROM equipment_test_types ett JOIN test_types t ON t.code = ett.test_type_code
                                WHERE ett.equipment_id = e.id
                                  AND (t.name_ko ILIKE :q OR t.code ILIKE :q
                                       OR EXISTS (SELECT 1 FROM unnest(t.keywords) k
                                                  WHERE k ILIKE :q OR :qRaw ILIKE '%' || k || '%'))))""");
        }
        List<String> regions = c.regions() == null ? List.of()
                : c.regions().stream().filter(StringUtils::hasText).map(String::trim).toList();
        if (!regions.isEmpty()) {
            params.addValue("regions", regions);
            and.add("e.region IN (:regions)");
        }
        if (c.institutionId() != null) {
            params.addValue("institutionId", c.institutionId());
            and.add("e.institution_id = :institutionId");
        }
        if (c.equipmentType() != null) {
            params.addValue("equipmentType", c.equipmentType().name());
            and.add("e.equipment_type = :equipmentType");
        }
        if (StringUtils.hasText(c.testType())) {
            params.addValue("testType", c.testType().trim());
            and.add("EXISTS (SELECT 1 FROM equipment_test_types ett WHERE ett.equipment_id = e.id "
                    + "AND ett.test_type_code = :testType)");
        }
        if (c.availability() != null) {
            params.addValue("availability", c.availability().name());
            and.add("e.availability = :availability");
        }
        if (c.externalOnly()) {
            and.add("e.access_scope = 'EXTERNAL_ALLOWED'");
        }
        if (!c.includeMock()) {
            and.add("NOT e.is_mock");
        }
        return String.join(" AND ", and);
    }

    /** LIKE 패턴 특수문자(\ % _)를 글자 그대로 찾도록 이스케이프 (PostgreSQL 기본 이스케이프 문자는 \) */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 매칭 후보: 그 시험유형을 할 수 있는 활성 장비 id (외부 id 순). region 이 null 이면 전체 */
    public List<Long> findCandidateIds(String testTypeCode, String region) {
        MapSqlParameterSource params = new MapSqlParameterSource("testType", testTypeCode);
        String regionFilter = "";
        if (StringUtils.hasText(region)) {
            params.addValue("region", region.trim());
            regionFilter = " AND e.region = :region";
        }
        return jdbc.queryForList("""
                SELECT e.id FROM equipment e
                WHERE e.is_active
                  AND EXISTS (SELECT 1 FROM equipment_test_types ett
                              WHERE ett.equipment_id = e.id AND ett.test_type_code = :testType)""" + regionFilter
                + " ORDER BY e.external_id", params, Long.class);
    }

    /** 기관 id → 활성 장비 수 */
    public Map<Long, Long> countByInstitution() {
        Map<Long, Long> result = new LinkedHashMap<>();
        jdbc.query("SELECT institution_id, count(*) n FROM equipment WHERE is_active GROUP BY institution_id",
                rs -> {
                    result.put(rs.getLong("institution_id"), rs.getLong("n"));
                });
        return result;
    }

    /** 시험유형 코드 → 그 시험을 할 수 있는 활성 장비 수 */
    public Map<String, Long> countByTestType() {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT ett.test_type_code, count(*) n FROM equipment_test_types ett
                JOIN equipment e ON e.id = ett.equipment_id
                WHERE e.is_active GROUP BY ett.test_type_code""", rs -> {
            result.put(rs.getString("test_type_code"), rs.getLong("n"));
        });
        return result;
    }

    /** 한 기관의 장비 유형별 활성 장비 수 (enum 순서) */
    public Map<EquipmentType, Long> countByEquipmentType(Long institutionId) {
        Map<EquipmentType, Long> result = new EnumMap<>(EquipmentType.class);
        jdbc.query("SELECT equipment_type, count(*) n FROM equipment WHERE is_active AND institution_id = :id "
                + "GROUP BY equipment_type", Map.of("id", institutionId), rs -> {
            result.put(EquipmentType.valueOf(rs.getString("equipment_type")), rs.getLong("n"));
        });
        return result;
    }

    /** 한 기관의 시험유형 코드 → 활성 장비 수 */
    public Map<String, Long> countByTestTypeForInstitution(Long institutionId) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT ett.test_type_code, count(*) n FROM equipment_test_types ett
                JOIN equipment e ON e.id = ett.equipment_id
                WHERE e.is_active AND e.institution_id = :id GROUP BY ett.test_type_code""",
                Map.of("id", institutionId), rs -> {
                    result.put(rs.getString("test_type_code"), rs.getLong("n"));
                });
        return result;
    }

    /** 한 시험유형을 할 수 있는 기관별 장비 수·예상 비용·기간·이용 범위 */
    public List<InstitutionTestTypeStat> statsByInstitution(String testTypeCode) {
        return jdbc.query("""
                SELECT e.institution_id, count(*) n,
                       min(ett.est_cost_min) cost_min, max(ett.est_cost_max) cost_max,
                       min(ett.est_duration_days) duration,
                       bool_or(e.access_scope = 'EXTERNAL_ALLOWED') any_external,
                       bool_and(e.access_scope = 'INTERNAL_ONLY') all_internal
                FROM equipment_test_types ett JOIN equipment e ON e.id = ett.equipment_id
                WHERE e.is_active AND ett.test_type_code = :code
                GROUP BY e.institution_id""", Map.of("code", testTypeCode), (rs, i) -> {
            AccessScope scope = rs.getBoolean("any_external") ? AccessScope.EXTERNAL_ALLOWED
                    : rs.getBoolean("all_internal") ? AccessScope.INTERNAL_ONLY : AccessScope.UNKNOWN;
            return new InstitutionTestTypeStat(rs.getLong("institution_id"), rs.getLong("n"),
                    (Integer) rs.getObject("cost_min"), (Integer) rs.getObject("cost_max"),
                    (Integer) rs.getObject("duration"), scope);
        });
    }

    /**
     * 장비의 시뮬레이션 프리셋 id (GET /equipment/{id} 의 simulation.presetIds).
     * simulation_presets 는 BE-B 테이블이라 읽기만 합니다. BE-B 서비스가 생기면 그쪽 호출로 바꿀 것.
     */
    public List<String> findPresetIds(Long equipmentId) {
        return jdbc.queryForList("SELECT preset_id FROM simulation_presets WHERE equipment_id = :id ORDER BY preset_id",
                Map.of("id", equipmentId), String.class);
    }
}
