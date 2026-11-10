package kr.astra.request.repository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 의뢰 번호 REQ-yyyyMMdd-NNNN (한국 날짜 기준, 하루마다 0001 부터).
 * 동시에 두 의뢰가 만들어져도 번호가 겹치지 않게 트랜잭션 범위 advisory lock 을 잡고 그날의 최대 번호 + 1 을 씁니다.
 * (lock 은 의뢰를 만드는 트랜잭션이 끝날 때 자동으로 풀림)
 */
@Component
@RequiredArgsConstructor
public class RequestNoGenerator {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** pg_advisory_xact_lock 키 (의뢰 번호 발급 전용, 임의의 고정값) */
    private static final long LOCK_KEY = 0x4153_5452_4152_4551L; // "ASTRAREQ"

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public String next() {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, LOCK_KEY);
        String prefix = "REQ-" + LocalDate.now(KST).format(DAY) + "-";
        String last = jdbc.queryForObject("SELECT MAX(request_no) FROM test_requests WHERE request_no LIKE ?",
                String.class, prefix + "%");
        int seq = last == null ? 1 : Integer.parseInt(last.substring(prefix.length())) + 1;
        return prefix + String.format("%04d", seq);
    }
}
