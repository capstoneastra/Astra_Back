-- 조직 유형에 대학(UNIVERSITY) 추가.
-- 대학은 기관(INSTITUTION)과 같은 방식으로 가입·승인되고 장비를 보유합니다.
-- V1 의 인라인 CHECK 는 PostgreSQL 이 organizations_org_type_check 로 이름을 붙입니다.

ALTER TABLE organizations DROP CONSTRAINT organizations_org_type_check;
ALTER TABLE organizations ADD CONSTRAINT organizations_org_type_check
    CHECK (org_type IN ('COMPANY','INSTITUTION','UNIVERSITY','ADMIN'));
