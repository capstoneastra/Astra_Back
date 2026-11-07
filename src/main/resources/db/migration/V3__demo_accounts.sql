-- =====================================================================
-- V3: 시연 계정 + 초기 기관 승인 처리 (BE-A)
--  * 시연 계정 5개: 관리자 1, 기업 1, 기관 담당자 2(ACTIVE), 승인 대기 기관 담당자 1(PENDING)
--  * 비밀번호는 BCrypt 해시만 저장. 원문은 저장소에 두지 않고 팀 채팅으로 공유
--  * V2 로 들어간 기관 9곳은 공개자료로 확인한 기관이라 승인(is_verified=TRUE) 처리
--    → 가입 화면(GET /auth/institutions)에서 선택 가능
-- =====================================================================

UPDATE organizations SET is_verified = TRUE WHERE org_type = 'INSTITUTION';

-- 조직: 플랫폼 운영(관리자 소속), 시연 기업
INSERT INTO organizations (org_type, name, is_verified)
VALUES ('ADMIN', 'ASTRA 플랫폼 운영', TRUE);
INSERT INTO organizations (org_type, name, business_reg_no, is_verified)
VALUES ('COMPANY', '가상 A기업', '123-45-67890', TRUE);

-- 관리자 (회원가입 불가, 여기서만 생성)
INSERT INTO users (organization_id, email, password_hash, name, role, status)
SELECT id, 'admin@astra.demo', '$2a$10$VEreyAS6ogOggY/RG5UviOv/iWYbRMrLr95dWutc5hK2y6hsJXvci', '플랫폼 관리자', 'ADMIN', 'ACTIVE'
FROM organizations WHERE org_type = 'ADMIN' AND name = 'ASTRA 플랫폼 운영';

-- 기업 사용자
INSERT INTO users (organization_id, email, password_hash, name, phone, role, status)
SELECT id, 'company@astra.demo', '$2a$10$VEreyAS6ogOggY/RG5UviOv/iWYbRMrLr95dWutc5hK2y6hsJXvci', '홍길동', '010-0000-0000', 'COMPANY_USER', 'ACTIVE'
FROM organizations WHERE org_type = 'COMPANY' AND name = '가상 A기업';

-- 기관 담당자 (관리자 승인 완료): 시연 시나리오의 진동시험기 보유 기관 2곳
--   대전테크노파크 우주·양자산업센터: ASTRA-CAND-0048 진동시험기
--   한국신뢰성기술센터: ASTRA-CAND-0044 진동시험기1
INSERT INTO users (organization_id, email, password_hash, name, phone, role, status, approved_by, approved_at)
SELECT o.id, 'manager1@astra.demo', '$2a$10$VEreyAS6ogOggY/RG5UviOv/iWYbRMrLr95dWutc5hK2y6hsJXvci', '김담당', '042-000-0001', 'INSTITUTION_MANAGER', 'ACTIVE', a.id, now()
FROM organizations o, users a
WHERE o.org_type = 'INSTITUTION' AND o.name = '대전테크노파크 우주·양자산업센터' AND a.email = 'admin@astra.demo';

INSERT INTO users (organization_id, email, password_hash, name, phone, role, status, approved_by, approved_at)
SELECT o.id, 'manager2@astra.demo', '$2a$10$VEreyAS6ogOggY/RG5UviOv/iWYbRMrLr95dWutc5hK2y6hsJXvci', '박담당', '042-000-0002', 'INSTITUTION_MANAGER', 'ACTIVE', a.id, now()
FROM organizations o, users a
WHERE o.org_type = 'INSTITUTION' AND o.name = '한국신뢰성기술센터' AND a.email = 'admin@astra.demo';

-- 승인 대기 기관 담당자 (관리자 승인 화면 시연용, 로그인하면 403 ACCOUNT_NOT_ACTIVE)
INSERT INTO users (organization_id, email, password_hash, name, phone, role, status)
SELECT id, 'pending@astra.demo', '$2a$10$VEreyAS6ogOggY/RG5UviOv/iWYbRMrLr95dWutc5hK2y6hsJXvci', '이대기', '055-000-0003', 'INSTITUTION_MANAGER', 'PENDING'
FROM organizations WHERE org_type = 'INSTITUTION' AND name = '한국재료연구원';
