-- =====================================================================
-- V4: 회원가입 이메일 인증 (BE-A)
--  * 가입 전에 이메일로 6자리 코드를 보내고, 맞으면 가입용 인증 토큰(verificationToken)을 발급
--  * 코드·토큰 원문은 저장하지 않고 SHA-256 해시만 저장
--  * 가입(POST /auth/signup) 시 토큰을 한 번 쓰면 used_at 을 채워 다시 쓸 수 없게 함
-- =====================================================================

CREATE TABLE email_verifications (
    id                BIGSERIAL PRIMARY KEY,
    email             VARCHAR(200) NOT NULL,             -- 소문자로 저장
    code_hash         VARCHAR(200) NOT NULL,             -- 6자리 코드의 SHA-256
    code_expires_at   TIMESTAMPTZ  NOT NULL,             -- 발송 후 10분
    attempt_count     INT          NOT NULL DEFAULT 0,   -- 틀린 횟수 (5회 틀리면 코드 무효)
    verified_at       TIMESTAMPTZ,                       -- 코드 확인 완료 시각
    token_hash        VARCHAR(200) UNIQUE,               -- 확인 후 발급한 가입용 토큰의 SHA-256
    token_expires_at  TIMESTAMPTZ,                       -- 확인 후 30분
    used_at           TIMESTAMPTZ,                       -- 가입에 사용한 시각
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_email_verifications_email ON email_verifications(email, created_at DESC);
