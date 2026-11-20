-- =====================================================================
-- V16: 첨부파일 종류 (BE-B, 2026-11 회의 결정: 시험 결과 보고서를 파일로 전달)
--  * GENERAL       : 의뢰 당사자가 올리는 일반 첨부 (시료 사진, 도면 등)
--  * RESULT_REPORT : 장비 보유 조직이 일정 확정 뒤 올리는 시험 성적서·보고서
--  * 파일 자체는 서버 디스크(astra.storage.dir)에 저장하고 여기에는 위치(storage_key)만 둠
-- =====================================================================
ALTER TABLE attachments
    ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT 'GENERAL'
        CHECK (category IN ('GENERAL', 'RESULT_REPORT'));
CREATE INDEX idx_attachments_request ON attachments(request_id);
