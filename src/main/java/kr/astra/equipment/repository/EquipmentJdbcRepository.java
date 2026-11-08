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

    /** 유사 장비 후보 한 줄: 같은 분류 여부와 이름 유사도(0~1) */
    public record SimilarRow(Long id, boolean sameCategory, double nameSimilarity) {
    }

    /**
     * 유사 장비 (GET /equipment/{id}/similar): 자기 자신 제외, 활성 장비 중
     * 시험유형이 하나라도 같거나, 같은 유형·분류이거나, 이름 유사도 0.3 이상.
     * 시험유형이 겹치는 장비 → 이름 유사도 높은 순 → 외부 id 순으로 최대 limit 개.
     */
    public List<SimilarRow> findSimilar(Long equipmentId, int limit) {
        return jdbc.query("""
                SELECT e.id,
                       (e.category IS NOT NULL AND e.category = me.category
                        AND e.equipment_type = me.equipment_type) AS same_category,
                       similarity(e.name, me.name) AS sim,
                       EXISTS (SELECT 1 FROM equipment_test_types a JOIN equipment_test_types b
                                 ON a.test_type_code = b.test_type_code
                               WHERE a.equipment_id = e.id AND b.equipment_id = me.id) AS shared_test_type
                FROM equipment e, equipment me
                WHERE me.id = :id AND e.is_active AND e.id <> me.id
                  AND (EXISTS (SELECT 1 FROM equipment_test_types a JOIN equipment_test_types b
                                 ON a.test_type_code = b.test_type_code
                               WHERE a.equipment_id = e.id AND b.equipment_id = me.id)
                       OR (e.category IS NOT NULL AND e.category = me.category
                           AND e.equipment_type = me.equipment_type)
                       OR similarity(e.name, me.name) >= 0.3)
                ORDER BY shared_test_type DESC, sim DESC, e.external_id
                LIMIT :limit""", Map.of("id", equipmentId, "limit", limit),
                (rs, i) -> new SimilarRow(rs.getLong("id"), rs.getBoolean("same_category"), rs.getDouble("sim")));
    }

    /** 중복 장비 그룹 한 개: 같은 지역·같은 시험유형의 활성 장비 id (외부 id 순) */
    public record DuplicateRow(String region, String testTypeCode, List<Long> equipmentIds) {
    }

    /**
     * 중복 장비 그룹 (GET /equipment/duplicates): 같은 지역 + 같은 시험유형에 활성 장비가 2대 이상인 묶음.
     * 지역이 없는 장비는 제외. region·testType 이 null 이면 전체.
     */
    public List<DuplicateRow> findDuplicateGroups(String region, String testTypeCode) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder filter = new StringBuilder();
        if (StringUtils.hasText(region)) {
            params.addValue("region", region.trim());
            filter.append(" AND e.region = :region");
        }
        if (StringUtils.hasText(testTypeCode)) {
            params.addValue("testType", testTypeCode.trim());
            filter.append(" AND ett.test_type_code = :testType");
        }
        return jdbc.query("""
                SELECT e.region, ett.test_type_code, array_agg(e.id ORDER BY e.external_id) AS ids
                FROM equipment e JOIN equipment_test_types ett ON ett.equipment_id = e.id
                WHERE e.is_active AND e.region IS NOT NULL""" + filter + """

                GROUP BY e.region, ett.test_type_code
                HAVING count(*) >= 2""", params, (rs, i) -> {
            Long[] ids = (Long[]) rs.getArray("ids").getArray();
            return new DuplicateRow(rs.getString("region"), rs.getString("test_type_code"), List.of(ids));
        });
    }

    // ------------------------------------------------------------ 대시보드 집계 (활성 장비만)

    /** 지역 → 장비 수 (지역 없는 장비 제외) */
    public Map<String, Long> countByRegion() {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query("SELECT region, count(*) n FROM equipment WHERE is_active AND region IS NOT NULL GROUP BY region",
                rs -> {
                    result.put(rs.getString("region"), rs.getLong("n"));
                });
        return result;
    }

    /** 장비 유형 → 장비 수 (enum 순서) */
    public Map<EquipmentType, Long> countByEquipmentTypeAll() {
        Map<EquipmentType, Long> result = new EnumMap<>(EquipmentType.class);
        jdbc.query("SELECT equipment_type, count(*) n FROM equipment WHERE is_active GROUP BY equipment_type", rs -> {
            result.put(EquipmentType.valueOf(rs.getString("equipment_type")), rs.getLong("n"));
        });
        return result;
    }

    /** 기관별 장비 수와 기관 확인(INSTITUTION_VERIFIED) 장비 수 */
    public record InstitutionVerificationRow(Long institutionId, long equipmentCount, long verifiedCount) {
    }

    public List<InstitutionVerificationRow> countVerificationByInstitution() {
        return jdbc.query("""
                SELECT institution_id, count(*) n,
                       count(*) FILTER (WHERE verification_status = 'INSTITUTION_VERIFIED') verified
                FROM equipment WHERE is_active GROUP BY institution_id""", (rs, i) ->
                new InstitutionVerificationRow(rs.getLong("institution_id"), rs.getLong("n"), rs.getLong("verified")));
    }

    /** 한 기관의 갱신 필요 장비 수: 기관 확인 전 / 마지막 갱신 90일 경과 / 둘 중 하나라도 (전체) */
    public record NeedsUpdateRow(long total, long notConfirmed, long outdated) {
    }

    public NeedsUpdateRow countNeedsUpdate(Long institutionId, int days) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE verification_status <> 'INSTITUTION_VERIFIED'
                                           OR info_updated_at < now() - make_interval(days => :days)) total,
                       count(*) FILTER (WHERE verification_status <> 'INSTITUTION_VERIFIED') not_confirmed,
                       count(*) FILTER (WHERE info_updated_at < now() - make_interval(days => :days)) outdated
                FROM equipment WHERE is_active AND institution_id = :id""", Map.of("id", institutionId, "days", days),
                (rs, i) -> new NeedsUpdateRow(rs.getLong("total"), rs.getLong("not_confirmed"), rs.getLong("outdated")));
    }

    /** 장비에 쓰인 지역 이름 (자연어 검색에서 "대전" 같은 지역을 알아볼 때) */
    public List<String> findRegions() {
        return jdbc.queryForList("SELECT DISTINCT region FROM equipment WHERE is_active AND region IS NOT NULL "
                + "ORDER BY region", Map.of(), String.class);
    }

    /** 갱신 알림 대상 장비 한 대 */
    public record ReminderRow(Long equipmentId, Long institutionId, String name) {
    }

    /**
     * 마지막 갱신이 정확히 days 일을 넘긴 지 하루가 안 된 활성 장비 (하루 한 번 돌면 장비마다 한 번만 걸림).
     * 예: days=90 이면 info_updated_at 이 91일 전 ~ 90일 전 사이.
     */
    public List<ReminderRow> findJustOutdated(int days) {
        return jdbc.query("""
                SELECT id, institution_id, name FROM equipment
                WHERE is_active
                  AND info_updated_at <  now() - make_interval(days => :days)
                  AND info_updated_at >= now() - make_interval(days => :days + 1)
                ORDER BY external_id""", Map.of("days", days),
                (rs, i) -> new ReminderRow(rs.getLong("id"), rs.getLong("institution_id"), rs.getString("name")));
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
