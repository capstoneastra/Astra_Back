-- =====================================================================
-- V13: 보완 요청 종류 (BE-B, 2026-11 회의 결정)
--  * SUPPLEMENT      : 기존 보완 요청 (부족한 자료를 다시 받음)
--  * ADDITIONAL_INFO : 검토 중 한 번 더 받는 추가 정보 (책임자명·성적서 종류·시료 처리·청구 방법)
-- =====================================================================
ALTER TABLE supplement_requests
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'SUPPLEMENT'
        CHECK (kind IN ('SUPPLEMENT', 'ADDITIONAL_INFO'));
