-- =====================================================================
-- V19: 계정 찾기 - 비밀번호 재설정 (BE-A)
--  * 코드 발송 → 코드 확인(재설정 토큰 발급, 15분) → 새 비밀번호 저장(토큰 사용 처리)
--  * 가입하지 않은 이메일도 같은 응답을 주기 위해 행은 만들되 user_id 를 비워 두고 메일은 보내지 않음
--    (60초 재발송 제한도 똑같이 걸려 계정이 있는지 드러나지 않음)
--  * 코드·토큰 원문은 저장하지 않고 SHA-256 해시만 저장
-- =====================================================================

CREATE TABLE password_resets (
    id                BIGSERIAL PRIMARY KEY,
    email             VARCHAR(200) NOT NULL,             -- 소문자로 저장
    user_id           BIGINT REFERENCES users(id) ON DELETE CASCADE, -- 가입하지 않은 이메일이면 NULL
    code_hash         VARCHAR(200) NOT NULL,             -- 6자리 코드의 SHA-256
    code_expires_at   TIMESTAMPTZ  NOT NULL,             -- 발송 후 10분
    attempt_count     INT          NOT NULL DEFAULT 0,   -- 틀린 횟수 (5회 틀리면 코드 무효)
    verified_at       TIMESTAMPTZ,                       -- 코드 확인 완료 시각
    token_hash        VARCHAR(200) UNIQUE,               -- 확인 후 발급한 재설정 토큰의 SHA-256
    token_expires_at  TIMESTAMPTZ,                       -- 확인 후 15분
    used_at           TIMESTAMPTZ,                       -- 새 비밀번호 저장에 사용한 시각
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_password_resets_email ON password_resets(email, created_at DESC);
