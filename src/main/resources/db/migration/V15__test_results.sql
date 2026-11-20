-- =====================================================================
-- V15: 시험 결과·성적서 (BE-B, 2026-11 회의 결정)
--  * ASTRA 는 장비 대여가 아니라 기관이 대신 시험하고 결과를 보내 주는 방식
--  * 의뢰당 결과 1건 (다시 등록하면 고침). 첫 버전은 파일 업로드 없이 성적서 번호·링크만
--  * 결과가 있어야 의뢰를 완료(COMPLETED)할 수 있음
-- =====================================================================
CREATE TABLE test_results (
    id            BIGSERIAL PRIMARY KEY,
    request_id    BIGINT        NOT NULL UNIQUE REFERENCES test_requests(id) ON DELETE CASCADE,
    summary       TEXT          NOT NULL,
    report_no     VARCHAR(100),
    report_url    VARCHAR(1000),
    registered_by BIGINT        NOT NULL REFERENCES users(id),
    registered_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
