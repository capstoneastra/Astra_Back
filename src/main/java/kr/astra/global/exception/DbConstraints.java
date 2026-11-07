package kr.astra.global.exception;

import java.sql.SQLException;
import java.util.Locale;

/**
 * DB 제약조건 위반을 구분하는 도우미. JPA 는 saveAndFlush() 해야 제약 위반이 그 자리에서 발생합니다.
 * <pre>
 * try {
 *     reservationRepository.saveAndFlush(reservation);
 * } catch (DataIntegrityViolationException e) {
 *     if (DbConstraints.violates(e, DbConstraints.RESERVATION_NO_OVERLAP)) throw new BusinessException(ErrorCode.RESERVATION_OVERLAP);
 *     if (DbConstraints.violates(e, DbConstraints.RESERVATION_ONE_CONFIRMED)) throw new BusinessException(ErrorCode.RESERVATION_ALREADY_CONFIRMED);
 *     throw e;
 * }
 * </pre>
 */
public final class DbConstraints {

    /** PostgreSQL SQLSTATE: 유니크 위반 */
    public static final String UNIQUE_VIOLATION = "23505";
    /** PostgreSQL SQLSTATE: EXCLUDE 제약 위반 (예약 시간 겹침) */
    public static final String EXCLUSION_VIOLATION = "23P01";

    /** schema.sql 의 제약·인덱스 이름 (이름을 바꾸면 여기도 같이 수정) */
    public static final String RESERVATION_NO_OVERLAP = "ex_resv_no_overlap";
    public static final String RESERVATION_ONE_CONFIRMED = "uq_resv_one_confirmed_per_request";
    public static final String SUPPLEMENT_ONE_OPEN = "uq_supp_one_open_per_request";

    private DbConstraints() {
    }

    /** 예외 원인 중 SQLException 의 SQLSTATE (없으면 null) */
    public static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /** 지정한 이름의 제약조건을 위반했는지 (PostgreSQL 오류 메시지에 제약 이름이 포함됨) */
    public static boolean violates(Throwable e, String constraintName) {
        String needle = constraintName.toLowerCase(Locale.ROOT);
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
