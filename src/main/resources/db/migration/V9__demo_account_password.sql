-- =====================================================================
-- V9: 시연 계정 비밀번호 재설정 (BE-A)
--  * V3·V8 의 시연 계정 해시는 원문이 공유된 적이 없어 아무도 로그인할 수 없었음
--  * 시연 계정 7개(@astra.demo)를 같은 비밀번호로 맞춤. 원문은 README "시연 계정" 참고
--    (시연 전용 계정이라 공개해도 되는 값으로 정함. 실제 사용자 계정에는 영향 없음)
-- =====================================================================

UPDATE users
SET password_hash = '$2a$10$r42IbAMGe46nKuY5sXvthujR4FpdxbsSnG.2q3f6dz2BocUXE2PJm',
    updated_at    = now()
WHERE email IN ('admin@astra.demo', 'company@astra.demo', 'company2@astra.demo',
                'manager1@astra.demo', 'manager2@astra.demo', 'univ1@astra.demo', 'pending@astra.demo');
