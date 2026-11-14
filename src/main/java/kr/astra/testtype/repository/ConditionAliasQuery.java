package kr.astra.testtype.repository;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository @RequiredArgsConstructor
public class ConditionAliasQuery {
    private final JdbcTemplate jdbc;
    public Map<String,List<String>> forType(String code) {
        Map<String,List<String>> result = new LinkedHashMap<>();
        jdbc.query("SELECT condition_key,alias FROM condition_aliases WHERE test_type_code=? ORDER BY condition_key,alias",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2)), code);
        return result;
    }
}
