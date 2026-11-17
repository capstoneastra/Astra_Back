package kr.astra.organization.entity;

import java.util.Set;

/**
 * DB 컬럼: organizations.org_type (schema.sql CHECK 제약과 값이 1:1)
 * 값을 바꾸려면 Flyway 마이그레이션으로 CHECK 도 함께 수정하고 FE 에 공지하세요.
 * Entity 에서는 {@code @Enumerated(EnumType.STRING)} 으로 매핑합니다.
 */
public enum OrgType {
    COMPANY,
    INSTITUTION,
    UNIVERSITY,
    ADMIN;

    /** 기관 방식(목록에서 선택하거나 신청 → 관리자 승인)으로 가입하는 유형: 시험기관·대학 */
    public static final Set<OrgType> INSTITUTION_TYPES = Set.of(INSTITUTION, UNIVERSITY);

    public boolean isInstitutionType() {
        return INSTITUTION_TYPES.contains(this);
    }
}
