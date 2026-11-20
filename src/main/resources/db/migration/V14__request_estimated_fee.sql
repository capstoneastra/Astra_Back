-- =====================================================================
-- V14: 기관이 적는 예상 이용료 (BE-B, 2026-11 회의 결정)
--  * 금액(원)과 메모. 장비 보유 조직이 검토 중 ~ 일정 조율 중에 입력·수정
-- =====================================================================
ALTER TABLE test_requests
    ADD COLUMN estimated_fee            BIGINT CHECK (estimated_fee >= 0),
    ADD COLUMN estimated_fee_note       VARCHAR(500),
    ADD COLUMN estimated_fee_updated_at TIMESTAMPTZ;
