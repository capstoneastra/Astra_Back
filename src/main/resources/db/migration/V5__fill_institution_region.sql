-- =====================================================================
-- V5: 지역이 비어 있던 기관·장비 채우기 (BE-A)
--  * 원본 JSON 에 '한국생산기술연구원 진주뿌리기술지원센터' 장비 31대의 region 이 없어서
--    V2 seed 에서 기관·장비 모두 region 이 NULL 로 들어감 → 지역 검색(GET /equipment?region=진주)에서 빠짐
--  * 기관명 기준으로 '진주'를 채움 (다른 기관과 같은 표기: 대전, 진주, 경남 ...)
--  * 이미 값이 있는 행은 건드리지 않음
-- =====================================================================

UPDATE organizations SET region = '진주'
WHERE org_type = 'INSTITUTION' AND name = '한국생산기술연구원 진주뿌리기술지원센터' AND region IS NULL;

UPDATE equipment e SET region = '진주'
FROM organizations o
WHERE o.id = e.institution_id
  AND o.org_type = 'INSTITUTION' AND o.name = '한국생산기술연구원 진주뿌리기술지원센터'
  AND e.region IS NULL;
