package kr.astra.request.entity;

/** DB 컬럼: attachments.category (V16 CHECK 제약과 값이 1:1) */
public enum AttachmentCategory {
    /** 의뢰 당사자가 올리는 일반 첨부 */
    GENERAL,
    /** 장비 보유 조직이 일정 확정 뒤 올리는 시험 성적서·보고서 */
    RESULT_REPORT
}
