package kr.astra.equipment.entity;

/**
 * DB 컬럼: equipment.access_scope (schema.sql CHECK 제약과 값이 1:1)
 * 값을 바꾸려면 Flyway 마이그레이션으로 CHECK 도 함께 수정하고 FE 에 공지하세요.
 * Entity 에서는 {@code @Enumerated(EnumType.STRING)} 으로 매핑합니다.
 */
public enum AccessScope {
    EXTERNAL_ALLOWED,
    INTERNAL_ONLY,
    UNKNOWN
}
