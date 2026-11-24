package kr.astra.equipment.entity;

/**
 * DB 컬럼: equipment.fee_unit (schema.sql CHECK 제약과 값이 1:1)
 * 값을 바꾸려면 Flyway 마이그레이션으로 CHECK 도 함께 수정하고 FE 에 공지하세요.
 * Entity 에서는 {@code @Enumerated(EnumType.STRING)} 으로 매핑합니다.
 */
public enum FeeUnit {
    HOUR,
    DAY,
    CASE,
    SAMPLE,
    NEGOTIABLE;

    /** 화면 표시 단위 (fee_text "금액원/단위") */
    public String label() {
        return switch (this) {
            case HOUR -> "시간";
            case DAY -> "일";
            case CASE -> "건";
            case SAMPLE -> "시료";
            case NEGOTIABLE -> "협의";
        };
    }

    /**
     * 이용료 표시 문구 (V17 에서 정한 한 형식): "12,000원/시간 (부가세 별도)", 금액·단위가 없거나 NEGOTIABLE 이면 "협의".
     * 부가세 여부를 모르면(null) 괄호를 붙이지 않습니다.
     */
    public static String format(Integer amount, FeeUnit unit, Boolean vatIncluded) {
        if (amount == null || unit == null || unit == NEGOTIABLE) {
            return NEGOTIABLE.label();
        }
        String vat = vatIncluded == null ? "" : vatIncluded ? " (부가세 포함)" : " (부가세 별도)";
        return String.format(java.util.Locale.ROOT, "%,d원/%s%s", amount, unit.label(), vat);
    }
}
