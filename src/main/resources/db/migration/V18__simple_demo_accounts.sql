-- =====================================================================
-- V18: 시연 계정 이메일·비밀번호를 짧게 (BE-A, 2026-11-25)
--  * 이메일 @astra.demo → @astra.kr, 기관 담당자는 manager → lab, 대학은 univ1 → univ
--  * 비밀번호는 모두 1234 (시연 전용이라 공개해도 되는 값. 가입·비밀번호 변경 규칙 8자 이상은 그대로)
--  * 실제 사용자 계정에는 영향 없음 (아래 7개 이메일만 바꿈)
-- =====================================================================

UPDATE users u
SET email = m.new_email,
    password_hash = '$2a$10$1uznX/m7XI3oQ9xKsQ4rBOAzsIMigElHq2gXAXORKJAIYH/CsjuhO',
    updated_at = now()
FROM (VALUES
        ('admin@astra.demo',    'admin@astra.kr'),
        ('company@astra.demo',  'company@astra.kr'),
        ('company2@astra.demo', 'company2@astra.kr'),
        ('manager1@astra.demo', 'lab1@astra.kr'),
        ('manager2@astra.demo', 'lab2@astra.kr'),
        ('univ1@astra.demo',    'univ@astra.kr'),
        ('pending@astra.demo',  'pending@astra.kr')
     ) AS m(old_email, new_email)
WHERE u.email = m.old_email;
