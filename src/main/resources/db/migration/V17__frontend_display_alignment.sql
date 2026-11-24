-- =====================================================================
-- V17: 프론트 화면 정리에 맞춘 데이터 정리 (BE-A, 2026-11-24)
--  1) 이용료: 부가세 포함 여부 칼럼 추가, fee_text 를 "금액원/단위 (부가세 별도|포함)" 한 형식으로 통일
--     · 단위: HOUR 시간 / DAY 일 / SAMPLE 시료 / CASE 건
--     · 금액이 없는 장비는 '협의' + fee_unit NEGOTIABLE (fee_amount·fee_vat_included 는 NULL)
--     · 원문에 VAT 표시가 없던 장비는 부가세 별도(FALSE)로 둠 (공용장비 이용료 관례, 모두 is_mock 장비)
--  2) 가동률: 시연용 장비(is_mock) 중 비어 있는 것에 25~84% 값을 채움 (external_id 기준이라 DB 마다 같은 값)
--  3) 문구: 의뢰 제목의 '[시연] ' 말머리, 알림 제목·본문의 '[REQ-…] ' 의뢰번호 제거,
--     '장비 보유 조직' → '시험기관' (데모 여부는 isMock, 어느 의뢰인지는 relatedRequestId 로 구분)
-- =====================================================================

-- 1) 이용료 ---------------------------------------------------------------
ALTER TABLE equipment ADD COLUMN fee_vat_included BOOLEAN;

UPDATE equipment SET fee_vat_included = TRUE
WHERE fee_amount IS NOT NULL AND fee_text ~* '(VAT\s*included|부가세\s*포함)';

UPDATE equipment SET fee_vat_included = FALSE
WHERE fee_amount IS NOT NULL AND fee_vat_included IS NULL;

UPDATE equipment
SET fee_text = to_char(fee_amount, 'FM999,999,999,990') || '원/'
        || CASE fee_unit WHEN 'HOUR' THEN '시간' WHEN 'DAY' THEN '일' WHEN 'SAMPLE' THEN '시료' ELSE '건' END
        || CASE WHEN fee_vat_included THEN ' (부가세 포함)' ELSE ' (부가세 별도)' END
WHERE fee_amount IS NOT NULL AND fee_unit IN ('HOUR', 'DAY', 'SAMPLE', 'CASE');

UPDATE equipment
SET fee_text = '협의', fee_unit = 'NEGOTIABLE', fee_vat_included = NULL
WHERE fee_amount IS NULL;

-- 2) 가동률 ---------------------------------------------------------------
UPDATE equipment
SET utilization_rate = 25 + mod(abs(hashtext(external_id)), 60)
WHERE is_mock AND utilization_rate IS NULL;

-- 3) 문구 -----------------------------------------------------------------
UPDATE test_requests
SET title = regexp_replace(title, '^\[시연\]\s*', '')
WHERE title LIKE '[시연]%';

UPDATE notifications
SET title = regexp_replace(replace(replace(title, '장비 보유 조직', '시험기관'), '[시연] ', ''),
                           '^\[REQ-[0-9]+-[0-9]+\]\s*', ''),
    body  = CASE WHEN body IS NULL THEN NULL
                 ELSE regexp_replace(replace(replace(body, '장비 보유 조직', '시험기관'), '[시연] ', ''),
                                     '^\[REQ-[0-9]+-[0-9]+\]\s*', '') END
WHERE title LIKE '[REQ-%' OR title LIKE '%장비 보유 조직%' OR title LIKE '%[시연]%'
   OR body LIKE '[REQ-%' OR body LIKE '%장비 보유 조직%' OR body LIKE '%[시연]%';
