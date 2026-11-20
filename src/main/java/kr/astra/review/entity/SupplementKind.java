package kr.astra.review.entity;

/**
 * DB 컬럼: supplement_requests.kind (V13 CHECK 제약과 값이 1:1)
 * <ul>
 *   <li>SUPPLEMENT: 부족한 자료를 다시 받는 일반 보완 요청</li>
 *   <li>ADDITIONAL_INFO: 검토 중 한 번 더 받는 추가 정보 (책임자명·성적서 종류·시료 처리·청구 방법)</li>
 * </ul>
 */
public enum SupplementKind {
    SUPPLEMENT,
    ADDITIONAL_INFO
}
