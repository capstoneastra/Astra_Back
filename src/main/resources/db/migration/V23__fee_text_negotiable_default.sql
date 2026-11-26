-- =====================================================================
-- V23: 이용료를 안 적고 등록한 장비의 빈 이용료 문구를 '협의'로 (BE-A, 2026-11-27)
--  시연 서버에서 앱으로 등록한 장비(열진공) 1대가 fee_text 비어 화면에 이용료가 안 나옴.
--  V17 규칙과 같게: 금액 없음 = '협의' + NEGOTIABLE, 부가세 여부 NULL. 이후 등록은 서버가 자동으로 채움
-- =====================================================================
UPDATE equipment
SET fee_text = '협의', fee_unit = 'NEGOTIABLE', fee_vat_included = NULL
WHERE coalesce(trim(fee_text), '') = '' AND fee_amount IS NULL;

-- 시연 기업 계정(company@astra.kr)의 조직 '가상 A기업' 빈 지역을 채움. 시연 장비가 가장 많은 사천으로 (실제 가입 조직은 건드리지 않음)
UPDATE organizations SET region = '사천'
WHERE org_type = 'COMPANY' AND name = '가상 A기업' AND coalesce(trim(region), '') = '';
