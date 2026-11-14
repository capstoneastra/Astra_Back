-- =====================================================================
-- ASTRA 우주항공 시험장비 공동활용 플랫폼 - 초기 DB 스키마 (PostgreSQL 16)
-- V6 반영 (2026-11-14). 운영 DB 변경은 db/migration의 새 버전으로만 적용
--
-- 규칙
--  * PK: BIGSERIAL id (외부 노출 코드는 별도 컬럼: external_id, request_no 등)
--  * enum은 PG ENUM 대신 VARCHAR + CHECK (JPA/ORM 매핑, 값 추가가 쉬움)
--  * 시각: TIMESTAMPTZ, 생성/수정 시각 created_at / updated_at
--  * 구조가 자주 바뀌는 값(의뢰서 본문, AI 메타데이터, 시뮬레이션 입출력)은 JSONB
--  * MOCK 데이터는 is_mock / source_type='MOCK' 로 반드시 구분 (README v0.6 정책)
--  * 사양 키(spec_key)·조건 키(condition_key): 단위 없는 snake_case (단위는 unit 컬럼). 원본 장비 JSON 의 key 를 그대로 따름
--    예) 장비 사양 max_payload(kg) ↔ 사용자 조건 sample_mass(kg). 어떤 조건을 어떤 사양과 비교할지는 test_condition_rules 가 정의
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- 장비명/시험명 부분일치·유사검색
CREATE EXTENSION IF NOT EXISTS btree_gist; -- 예약 시간 겹침 방지(EXCLUDE 제약)에 필요
-- CREATE EXTENSION IF NOT EXISTS vector; -- (선택) 자연어 의미검색 도입 시 pgvector

-- ---------------------------------------------------------------------
-- 1. 사용자 / 조직
-- ---------------------------------------------------------------------
CREATE TABLE organizations (
    id                BIGSERIAL PRIMARY KEY,
    org_type          VARCHAR(20)  NOT NULL CHECK (org_type IN ('COMPANY','INSTITUTION','ADMIN')),
    name              VARCHAR(200) NOT NULL,
    parent_name       VARCHAR(200),             -- 예: '대전테크노파크' (하위 센터의 상위기관)
    business_reg_no   VARCHAR(20),              -- 기업 사업자번호
    region            VARCHAR(50),              -- 대전, 진주, 경남 ...
    address           VARCHAR(300),
    phone             VARCHAR(30),
    email             VARCHAR(200),
    homepage_url      VARCHAR(500),
    has_secure_facility BOOLEAN,
    security_note VARCHAR(1000),
    is_verified       BOOLEAN      NOT NULL DEFAULT FALSE,   -- 가입으로 생긴 기업·신규 기관은 관리자가 회원 승인할 때 TRUE
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (org_type, name)
);

CREATE TABLE users (
    id                BIGSERIAL PRIMARY KEY,
    organization_id   BIGINT       REFERENCES organizations(id),
    email             VARCHAR(200) NOT NULL UNIQUE,
    password_hash     VARCHAR(200) NOT NULL,
    name              VARCHAR(50)  NOT NULL,
    phone             VARCHAR(30),
    role              VARCHAR(30)  NOT NULL CHECK (role IN ('COMPANY_USER','INSTITUTION_MANAGER','ADMIN')),
                      -- ADMIN = 중개플랫폼 관리자(마스터, 전체 권한). 회원가입 불가, 초기 세팅으로만 생성
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PENDING','REJECTED','SUSPENDED')),
                      -- 기업·기관 담당자 모두 PENDING 으로 시작 → 관리자 승인 후 ACTIVE (가입 전 이메일 인증 필수)
    approved_by       BIGINT       REFERENCES users(id),
    approved_at       TIMESTAMPTZ,
    reject_reason     VARCHAR(500),
    last_login_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_users_org ON users(organization_id);

CREATE TABLE refresh_tokens (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash        VARCHAR(200) NOT NULL UNIQUE,
    expires_at        TIMESTAMPTZ  NOT NULL,
    revoked_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 회원가입 이메일 인증 (V4). 6자리 코드 → 확인되면 가입용 토큰 발급. 코드·토큰은 SHA-256 해시만 저장
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

-- ---------------------------------------------------------------------
-- 2. 장비 / 시험유형  (기능 1·2·3·4·12)
-- ---------------------------------------------------------------------
CREATE TABLE spec_definitions (
    spec_key VARCHAR(80) PRIMARY KEY,
    name_ko VARCHAR(100) NOT NULL,
    base_unit VARCHAR(30),
    value_type VARCHAR(10) NOT NULL CHECK (value_type IN ('NUMBER','RANGE','TEXT')),
    UNIQUE (spec_key, base_unit)
);

CREATE TABLE test_types (
    code              VARCHAR(50)  PRIMARY KEY,         -- VIBRATION, TENSILE, ELECTRICAL_RF_ANALYSIS ...
    name_ko           VARCHAR(100) NOT NULL,            -- 진동시험
    description       TEXT,
    keywords          TEXT[]       NOT NULL DEFAULT '{}', -- 자연어 검색용 동의어: {'진동','가진','sine','random'}
    display_order     INT          NOT NULL DEFAULT 0
);

-- 시험유형별 조건 규칙: 사용자가 입력할 조건 키 ↔ 비교할 장비 사양 키 ↔ 판정 방식
-- 사용처: 조건 입력폼(FE), AI 누락 분석(is_required), 매칭 판정(spec_key + comparator)
CREATE TABLE test_condition_rules (
    test_type_code    VARCHAR(50)  NOT NULL REFERENCES test_types(code) ON DELETE CASCADE,
    condition_key     VARCHAR(80)  NOT NULL,         -- sample_mass, frequency, sample_size_x ...
    label             VARCHAR(100) NOT NULL,         -- 시료 질량
    value_type        VARCHAR(10)  NOT NULL CHECK (value_type IN ('NUMBER','RANGE','TEXT')),
    unit              VARCHAR(30),                   -- 기준 단위 (비교 전 이 단위로 환산)
    is_required       BOOLEAN      NOT NULL DEFAULT FALSE, -- TRUE 인데 비어 있으면 AI 분석이 MISSING 처리
    spec_key          VARCHAR(80) REFERENCES spec_definitions(spec_key),                   -- 비교할 equipment_specs.spec_key (NULL = 비교 안 함, 기관 확인)
    comparator        VARCHAR(20)  NOT NULL DEFAULT 'NONE'
                      CHECK (comparator IN ('RANGE_WITHIN','MAX_LIMIT','MIN_LIMIT','NONE')),
                      -- RANGE_WITHIN: 요구 범위가 장비 범위 안 / MAX_LIMIT: 요구값 ≤ 장비값 / MIN_LIMIT: 장비값 ≤ 요구값(분해능 등)
    display_order     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (test_type_code, condition_key),
    FOREIGN KEY (spec_key,unit) REFERENCES spec_definitions(spec_key,base_unit)
);

CREATE TABLE condition_aliases (
    test_type_code VARCHAR(50) NOT NULL,
    condition_key VARCHAR(80) NOT NULL,
    alias VARCHAR(100) NOT NULL,
    PRIMARY KEY (test_type_code, condition_key, alias),
    FOREIGN KEY (test_type_code, condition_key) REFERENCES test_condition_rules(test_type_code, condition_key) ON DELETE CASCADE
);

CREATE TABLE equipment (
    id                BIGSERIAL PRIMARY KEY,
    external_id       VARCHAR(30)  NOT NULL UNIQUE,       -- 초기 적재: ASTRA-CAND-0001 (원본 internalId 유지) / 기관 신규 등록: ASTRA-EQ-0072 (id 4자리)
    institution_id    BIGINT       NOT NULL REFERENCES organizations(id),
    name              VARCHAR(200) NOT NULL,
    model             VARCHAR(200),
    manufacturer      VARCHAR(200),
    category          VARCHAR(100),                       -- '검사/시험장비' 등 원본 분류
    equipment_type    VARCHAR(30)  NOT NULL CHECK (equipment_type IN ('MEASUREMENT','MANUFACTURING','TEST','SOFTWARE','SUPPORT')),
    region            VARCHAR(50),
    description       TEXT,
    image_url         VARCHAR(500),
    -- 이용 조건
    access_scope      VARCHAR(30)  CHECK (access_scope IN ('EXTERNAL_ALLOWED','INTERNAL_ONLY','UNKNOWN')),
    fee_text          VARCHAR(200),                       -- 원문 그대로 '12000 KRW/hour VAT excluded'
    fee_amount        INT,                                -- 파싱된 숫자(원)
    fee_unit          VARCHAR(20)  CHECK (fee_unit IN ('HOUR','DAY','CASE','SAMPLE','NEGOTIABLE')),
    duration_text     VARCHAR(200),
    utilization_rate  NUMERIC(5,2),                       -- 가동률 %
    availability      VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN' CHECK (availability IN ('AVAILABLE','BUSY','MAINTENANCE','UNAVAILABLE','UNKNOWN')),
    availability_updated_at TIMESTAMPTZ,                  -- 기관이 가용 상태를 마지막으로 확인한 시각
    availability_updated_by BIGINT REFERENCES users(id),  -- 상태 확인자. NULL이면 기관 확인 필요
    availability_note VARCHAR(500),                       -- 판단 근거 또는 DEMO 표시
    availability_reminded_at TIMESTAMPTZ,                 -- 이번 상태 확인 주기의 알림 시각
    lead_time_days INT CHECK (lead_time_days >= 0),         -- 착수까지 대기 일수. NULL은 미확인
    operation_mode VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (operation_mode IN ('INSTITUTION_OPERATED','SUPERVISED_SELF_SERVICE','SELF_SERVICE','UNKNOWN')),
    -- 출처 / 검증
    source_type       VARCHAR(20)  CHECK (source_type IN ('WEB','PDF','INSTITUTION','MANUAL')),
    source_url        VARCHAR(1000),
    source_file       VARCHAR(200),
    source_page       INT,
    verification_status      VARCHAR(30) NOT NULL DEFAULT 'NEEDS_MANUAL_REVIEW'
                             CHECK (verification_status IN ('NEEDS_MANUAL_REVIEW','LISTING_CONFIRMED','INSTITUTION_VERIFIED')),
                             -- 공개자료 수집분은 NEEDS_MANUAL_REVIEW/LISTING_CONFIRMED, 기관 담당자가 수정·확인하면 INSTITUTION_VERIFIED
    spec_verification_status VARCHAR(30) NOT NULL DEFAULT 'NOT_VERIFIED'
                             CHECK (spec_verification_status IN ('NOT_VERIFIED','UNVERIFIED','VERIFIED')),
    aerospace_relevance      VARCHAR(30) NOT NULL DEFAULT 'NOT_ASSESSED',
    is_mock           BOOLEAN      NOT NULL DEFAULT FALSE,   -- 시연용 가상 데이터 포함 여부
    -- 시뮬레이션 (기능 15)
    simulation_capability VARCHAR(30) CHECK (simulation_capability IN ('CALCULATED_DEMO','RULE_BASED_DEMO','VISUALIZATION_DEMO','WORKFLOW_DEMO')),
    info_updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),  -- 화면의 '정보 갱신일' (기관 수정·확인 시 갱신)
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by        BIGINT       REFERENCES users(id),   -- NULL = 관리자 초기 적재(import)
    updated_by        BIGINT       REFERENCES users(id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_equipment_institution ON equipment(institution_id);
CREATE INDEX idx_equipment_region      ON equipment(region);
CREATE INDEX idx_equipment_type        ON equipment(equipment_type);
CREATE INDEX idx_equipment_name_trgm   ON equipment USING gin (name gin_trgm_ops);
CREATE INDEX idx_equipment_model_trgm  ON equipment USING gin (model gin_trgm_ops);

-- 장비 사양: 실제 사양(REAL)과 시연용(MOCK)을 같은 테이블에, source_type 으로 구분
-- JSON specifications -> REAL, demoSpecifications -> MOCK (절대 섞지 말 것)
CREATE TABLE equipment_specs (
    id                BIGSERIAL PRIMARY KEY,
    equipment_id      BIGINT       NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    spec_key          VARCHAR(80)  NOT NULL REFERENCES spec_definitions(spec_key),        -- frequency, max_payload, max_fixture_mass, measurement_range_x ...
    label             VARCHAR(100) NOT NULL,
    value_num         NUMERIC,                      -- 단일값
    min_num           NUMERIC,                      -- 범위 최소
    max_num           NUMERIC,                      -- 범위 최대
    value_text        VARCHAR(300),                 -- 숫자로 표현 불가한 값
    unit              VARCHAR(30),
    original_unit VARCHAR(30),
    source_type       VARCHAR(10)  NOT NULL CHECK (source_type IN ('REAL','MOCK')),
    verification_status VARCHAR(30) NOT NULL DEFAULT 'NOT_VERIFIED',
    usage_scope       VARCHAR(20)  NOT NULL DEFAULT 'GENERAL' CHECK (usage_scope IN ('GENERAL','DEMO_ONLY')),
    UNIQUE (equipment_id, spec_key, source_type),
    FOREIGN KEY (spec_key,unit) REFERENCES spec_definitions(spec_key,base_unit),
    CONSTRAINT ck_specs_value CHECK (
        (value_num IS NOT NULL AND min_num IS NULL AND max_num IS NULL AND value_text IS NULL)
        OR (value_num IS NULL AND min_num IS NOT NULL AND max_num IS NOT NULL AND min_num <= max_num AND value_text IS NULL)
        OR (value_num IS NULL AND min_num IS NULL AND max_num IS NULL AND value_text IS NOT NULL AND length(trim(value_text)) > 0))
);
CREATE INDEX idx_specs_key ON equipment_specs(spec_key);

-- 장비 정보 변경 이력 (기관 담당자가 무엇을 바꿨는지)
CREATE TABLE equipment_change_logs (
    id                BIGSERIAL PRIMARY KEY,
    equipment_id      BIGINT       NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    changed_by        BIGINT       NOT NULL REFERENCES users(id),
    action            VARCHAR(20)  NOT NULL CHECK (action IN ('CREATE','UPDATE','SPECS_UPDATE','TEST_TYPES_UPDATE','CONFIRM','DEACTIVATE','TRANSFER')),
    changes           JSONB,          -- {"feeAmount": {"before": null, "after": 300000}, ...}
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_ecl_equipment ON equipment_change_logs(equipment_id, created_at DESC);

-- 장비 ↔ 시험유형 (M:N) + 시험 정보 안내용 예상 비용/기간 (기능 4)
CREATE TABLE equipment_test_types (
    equipment_id      BIGINT       NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    test_type_code    VARCHAR(50)  NOT NULL REFERENCES test_types(code),
    est_cost_min      INT,                          -- 원
    est_cost_max      INT,
    est_duration_days INT,
    note              VARCHAR(300),
    is_accredited BOOLEAN,
    accreditation_no VARCHAR(100),
    accreditation_valid_until DATE,
    CONSTRAINT ck_accreditation_evidence CHECK (is_accredited IS DISTINCT FROM TRUE
        OR (accreditation_no IS NOT NULL AND length(trim(accreditation_no)) > 0 AND accreditation_valid_until IS NOT NULL)),
    PRIMARY KEY (equipment_id, test_type_code)
);
CREATE INDEX idx_ett_test_type ON equipment_test_types(test_type_code);

-- ---------------------------------------------------------------------
-- 3. AI 상담 / 시험조건  (기능 5·6)
-- ---------------------------------------------------------------------
CREATE TABLE equipment_downtimes (
    id BIGSERIAL PRIMARY KEY,
    equipment_id BIGINT NOT NULL REFERENCES equipment(id),
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    created_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (end_at > start_at)
);
CREATE INDEX idx_downtimes_equipment_period ON equipment_downtimes(equipment_id,start_at,end_at);

CREATE TABLE chat_sessions (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES users(id),
    title             VARCHAR(200),
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CLOSED')),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_sessions_user ON chat_sessions(user_id, created_at DESC);

CREATE TABLE chat_messages (
    id                BIGSERIAL PRIMARY KEY,
    session_id        BIGINT       NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role              VARCHAR(20)  NOT NULL CHECK (role IN ('USER','ASSISTANT','SYSTEM')),
    content           TEXT         NOT NULL,
    metadata          JSONB,          -- 추출된 조건, 추천 장비 id, 모델명, 토큰수 등
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_messages_session ON chat_messages(session_id, id);

-- 시험조건 묶음 (상담 결과물 = 매칭·의뢰서의 입력)
CREATE TABLE test_requirements (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES users(id),
    chat_session_id   BIGINT       REFERENCES chat_sessions(id),
    test_type_code    VARCHAR(50)  REFERENCES test_types(code),
    title             VARCHAR(200),
    purpose           TEXT,                          -- 시험 목적
    sample_name       VARCHAR(200),                  -- 시료명
    sample_quantity   INT,
    status            VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','CONFIRMED','ARCHIVED')),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_requirements_user ON test_requirements(user_id, created_at DESC);

-- 개별 조건 (질량 15kg, 주파수 20~2000Hz, 고정구 미정 ...)
CREATE TABLE requirement_conditions (
    id                BIGSERIAL PRIMARY KEY,
    requirement_id    BIGINT       NOT NULL REFERENCES test_requirements(id) ON DELETE CASCADE,
    condition_key     VARCHAR(80)  NOT NULL,         -- test_condition_rules.condition_key (sample_mass, fixture_mass, frequency ...)
    label             VARCHAR(100) NOT NULL,
    value_num         NUMERIC,
    min_num           NUMERIC,
    max_num           NUMERIC,
    value_text        VARCHAR(300),
    unit              VARCHAR(30),
    status            VARCHAR(20)  NOT NULL DEFAULT 'CONFIRMED'
                      CHECK (status IN ('CONFIRMED','UNCONFIRMED','MISSING')),   -- 미확인 정보 구분
    source            VARCHAR(20)  NOT NULL DEFAULT 'USER'
                      CHECK (source IN ('USER','AI_EXTRACTED','INSTITUTION')),
    confidence        NUMERIC(3,2),                  -- AI 추출 신뢰도 0~1
    note              VARCHAR(300),
    UNIQUE (requirement_id, condition_key)
);

-- ---------------------------------------------------------------------
-- 4. AI 매칭 / 비교  (기능 7·12)
-- ---------------------------------------------------------------------
CREATE TABLE match_runs (
    id                BIGSERIAL PRIMARY KEY,
    requirement_id    BIGINT       NOT NULL REFERENCES test_requirements(id) ON DELETE CASCADE,
    algorithm_version VARCHAR(30)  NOT NULL,         -- 신규 'rule-v2', 기존 'rule-v1' 이력 보존
    requirement_snapshot JSONB     NOT NULL,         -- 실행 당시 조건 스냅샷
    created_by        BIGINT       REFERENCES users(id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE match_results (
    id                BIGSERIAL PRIMARY KEY,
    match_run_id      BIGINT       NOT NULL REFERENCES match_runs(id) ON DELETE CASCADE,
    equipment_id      BIGINT       NOT NULL REFERENCES equipment(id),
    rank              INT          NOT NULL,
    score             NUMERIC(5,2),                  -- 0~100
    verdict           VARCHAR(20)  NOT NULL CHECK (verdict IN ('SUITABLE','PARTIAL','UNSUITABLE','UNKNOWN')),
    reason            TEXT,                          -- AI 추천 이유
    used_mock_data    BOOLEAN      NOT NULL DEFAULT FALSE,
    UNIQUE (match_run_id, equipment_id),
    UNIQUE (match_run_id, rank)
);

-- 조건별 충족/불일치/미확인 비교표
CREATE TABLE match_condition_checks (
    id                BIGSERIAL PRIMARY KEY,
    match_result_id   BIGINT       NOT NULL REFERENCES match_results(id) ON DELETE CASCADE,
    condition_key     VARCHAR(80)  NOT NULL,
    required_text     VARCHAR(200),                  -- '20~2000 Hz'
    equipment_text    VARCHAR(200),                  -- '10~3000 Hz'
    result            VARCHAR(20)  NOT NULL CHECK (result IN ('MET','NOT_MET','UNKNOWN')),
    note              VARCHAR(300)
);
CREATE INDEX idx_mcc_result ON match_condition_checks(match_result_id);

-- ---------------------------------------------------------------------
-- 5. 시험 의뢰 / 검토 / 보완  (기능 8·9·10)
-- ---------------------------------------------------------------------
CREATE TABLE test_requests (
    id                BIGSERIAL PRIMARY KEY,
    request_no        VARCHAR(30)  NOT NULL UNIQUE,  -- REQ-20261105-0001
    requester_id      BIGINT       NOT NULL REFERENCES users(id),
    company_org_id    BIGINT       REFERENCES organizations(id),
    institution_id    BIGINT       NOT NULL REFERENCES organizations(id),
    equipment_id      BIGINT       NOT NULL REFERENCES equipment(id),
    requirement_id    BIGINT       REFERENCES test_requirements(id),
    match_result_id   BIGINT       REFERENCES match_results(id),   -- 어떤 추천에서 왔는지
    assignee_id       BIGINT       REFERENCES users(id),            -- 기관 담당자
    title             VARCHAR(200) NOT NULL,
    status            VARCHAR(30)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
                        'DRAFT',                -- 작성 중 (AI 초안 포함)
                        'SUBMITTED',            -- 접수
                        'UNDER_REVIEW',         -- 검토 중
                        'SUPPLEMENT_REQUESTED', -- 보완 요청
                        'RESUBMITTED',          -- 보완 재제출
                        'SCHEDULING',           -- 일정 협의
                        'CONFIRMED',            -- 예약 확정
                        'COMPLETED',            -- 시험 완료
                        'REJECTED',             -- 반려
                        'CANCELED')),           -- 기업 취소
    current_revision_no INT        NOT NULL DEFAULT 1,
    desired_start_date DATE,
    desired_end_date  DATE,
    submitted_at      TIMESTAMPTZ,
    closed_at         TIMESTAMPTZ,
    version           INT          NOT NULL DEFAULT 0,   -- 낙관적 락 (동시 상태변경 방지)
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_requests_requester   ON test_requests(requester_id, created_at DESC);
CREATE INDEX idx_requests_institution ON test_requests(institution_id, status);
CREATE INDEX idx_requests_equipment   ON test_requests(equipment_id);

-- 제출본 이력 (상태와 분리 - "누가 무엇을 보완했는지")
CREATE TABLE test_request_revisions (
    id                BIGSERIAL PRIMARY KEY,
    request_id        BIGINT       NOT NULL REFERENCES test_requests(id) ON DELETE CASCADE,
    revision_no       INT          NOT NULL,
    content           JSONB        NOT NULL,   -- 의뢰서 본문 전체 스냅샷 (스키마는 API 명세 참조)
    is_ai_generated   BOOLEAN      NOT NULL DEFAULT FALSE,
    is_submitted      BOOLEAN      NOT NULL DEFAULT FALSE,  -- FALSE면 편집 가능한 작업본
    change_summary    VARCHAR(500),            -- '고정구 질량 2kg, 준비완료일 추가'
    author_id         BIGINT       NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    submitted_at      TIMESTAMPTZ,
    UNIQUE (request_id, revision_no)
);

-- 상태 변경 이력 (감사 로그)
CREATE TABLE request_status_history (
    id                BIGSERIAL PRIMARY KEY,
    request_id        BIGINT       NOT NULL REFERENCES test_requests(id) ON DELETE CASCADE,
    from_status       VARCHAR(30),
    to_status         VARCHAR(30)  NOT NULL,
    changed_by        BIGINT       NOT NULL REFERENCES users(id),
    reason            VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_rsh_request ON request_status_history(request_id, created_at);

-- 기관의 보완 요청
CREATE TABLE supplement_requests (
    id                BIGSERIAL PRIMARY KEY,
    request_id        BIGINT       NOT NULL REFERENCES test_requests(id) ON DELETE CASCADE,
    based_revision_no INT          NOT NULL,         -- 어떤 제출본을 보고 요청했는지
    requested_by      BIGINT       NOT NULL REFERENCES users(id),
    message           TEXT         NOT NULL,
    items             JSONB        NOT NULL DEFAULT '[]',
                      -- [{"fieldKey":"fixture_mass","label":"고정구 질량","required":true}, ...]
                      -- fieldKey = 조건 키(fixture_mass) 또는 의뢰서 content 경로(sample.readyDate)
    due_date          DATE,
    status            VARCHAR(20)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESPONDED','CLOSED')),
    response_revision_no INT,                        -- 보완 제출된 revision 번호
    response_message  TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    responded_at      TIMESTAMPTZ
);
CREATE INDEX idx_supp_request ON supplement_requests(request_id);
-- 답변 대기(OPEN) 중인 보완 요청은 의뢰당 1개만 (버튼 중복 클릭 방지)
CREATE UNIQUE INDEX uq_supp_one_open_per_request ON supplement_requests(request_id) WHERE status = 'OPEN';

CREATE TABLE attachments (
    id                BIGSERIAL PRIMARY KEY,
    request_id        BIGINT       NOT NULL REFERENCES test_requests(id) ON DELETE CASCADE,
    revision_no       INT,
    file_name         VARCHAR(300) NOT NULL,
    storage_key       VARCHAR(500) NOT NULL,          -- S3/로컬 경로
    mime_type         VARCHAR(100),
    size_bytes        BIGINT,
    uploaded_by       BIGINT       NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 6. 예약  (기능 11)
-- ---------------------------------------------------------------------
CREATE TABLE reservations (
    id                BIGSERIAL PRIMARY KEY,
    request_id        BIGINT       NOT NULL REFERENCES test_requests(id) ON DELETE CASCADE,
    equipment_id      BIGINT       NOT NULL REFERENCES equipment(id),
    start_at          TIMESTAMPTZ  NOT NULL,
    end_at            TIMESTAMPTZ  NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PROPOSED'
                      CHECK (status IN ('PROPOSED','COUNTER_PROPOSED','CONFIRMED','CANCELED')),
    proposed_by       BIGINT       NOT NULL REFERENCES users(id),
    confirmed_by      BIGINT       REFERENCES users(id),
    confirmed_at      TIMESTAMPTZ,
    external_system   VARCHAR(50),                    -- 향후 기관 예약시스템 연동
    external_ref      VARCHAR(100),
    note              VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (end_at > start_at),
    CONSTRAINT ex_resv_no_overlap EXCLUDE USING gist (
        equipment_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    ) WHERE (status = 'CONFIRMED')
);
-- 중복 예약 방지 규칙
--  1) ex_resv_no_overlap: 같은 장비의 CONFIRMED 예약끼리 시간이 겹치면 DB가 저장 거부 (SQLSTATE 23P01)
--     '[)' 구간이라 10:00~12:00 과 12:00~14:00 처럼 끝과 시작이 맞닿는 건 허용
--     기관 담당자 2명이 동시에 확정해도 하나만 성공 (애플리케이션 검사만으로는 못 막는 경우)
--  2) uq_resv_one_confirmed_per_request: 의뢰 1건당 CONFIRMED 예약은 1개 (SQLSTATE 23505)
CREATE INDEX idx_resv_equipment_time ON reservations(equipment_id, start_at, end_at);
CREATE UNIQUE INDEX uq_resv_one_confirmed_per_request ON reservations(request_id) WHERE status = 'CONFIRMED';

-- ---------------------------------------------------------------------
-- 7. AI 자동 추진  (기능 13)
-- ---------------------------------------------------------------------
CREATE TABLE ai_pipeline_runs (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES users(id),
    chat_session_id   BIGINT       REFERENCES chat_sessions(id),
    input_text        TEXT,                           -- 세션 없이 문장으로 시작할 때
    requirement_id    BIGINT       REFERENCES test_requirements(id),
    match_run_id      BIGINT       REFERENCES match_runs(id),
    draft_request_id  BIGINT       REFERENCES test_requests(id),
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING','RUNNING','WAITING_USER','SUCCEEDED','FAILED','CANCELED')),
    current_step      VARCHAR(20)  CHECK (current_step IN ('ANALYZE','SEARCH','MATCH','DRAFT','DONE')),
    steps_log         JSONB        NOT NULL DEFAULT '[]', -- [{step, status, startedAt, endedAt, message}]
    error_message     TEXT,
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 8. 가상 시뮬레이션  (기능 15)  - 전부 DEMO, 실장비 검증 아님
-- ---------------------------------------------------------------------
CREATE TABLE simulation_models (
    model_type        VARCHAR(50)  PRIMARY KEY,       -- SDOF_BASE_EXCITATION ...
    description       TEXT         NOT NULL,
    required_input_fields TEXT[]   NOT NULL,
    limitation        TEXT         NOT NULL,          -- 화면에 반드시 노출
    capability        VARCHAR(30)  NOT NULL CHECK (capability IN ('CALCULATED_DEMO','RULE_BASED_DEMO','VISUALIZATION_DEMO','WORKFLOW_DEMO')),
    model_version     VARCHAR(20)  NOT NULL DEFAULT '0.6',
    is_implemented    BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE simulation_presets (
    preset_id         VARCHAR(20)  PRIMARY KEY,       -- SIM-0001
    equipment_id      BIGINT       NOT NULL REFERENCES equipment(id) ON DELETE CASCADE,
    model_type        VARCHAR(50)  NOT NULL REFERENCES simulation_models(model_type),
    simulation_type   VARCHAR(50)  NOT NULL,          -- MANUFACTURING, RF, VIBRATION ...
    input_parameters  JSONB        NOT NULL,
    specs_snapshot    JSONB,                          -- equipmentDemoSpecsSnapshot
    assumptions       JSONB,
    is_mock           BOOLEAN      NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_presets_equipment ON simulation_presets(equipment_id);

CREATE TABLE simulation_runs (
    id                BIGSERIAL PRIMARY KEY,
    preset_id         VARCHAR(20)  REFERENCES simulation_presets(preset_id),
    equipment_id      BIGINT       NOT NULL REFERENCES equipment(id),
    user_id           BIGINT       REFERENCES users(id),
    requirement_id    BIGINT       REFERENCES test_requirements(id),
    model_type        VARCHAR(50)  NOT NULL REFERENCES simulation_models(model_type),
    model_version     VARCHAR(20)  NOT NULL,
    input_parameters  JSONB        NOT NULL,
    output            JSONB,                          -- 그래프 시계열, 판정 등
    status            VARCHAR(20)  NOT NULL DEFAULT 'NOT_EXECUTED'
                      CHECK (status IN ('NOT_EXECUTED','RUNNING','SUCCEEDED','FAILED')),
    is_mock           BOOLEAN      NOT NULL DEFAULT TRUE,
    warning           TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_simruns_user ON simulation_runs(user_id, created_at DESC);

-- ---------------------------------------------------------------------
-- 9. 알림  (기능 9·10·14)
-- ---------------------------------------------------------------------
CREATE TABLE notifications (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type              VARCHAR(40)  NOT NULL,          -- REQUEST_SUBMITTED, SUPPLEMENT_REQUESTED, RESERVATION_CONFIRMED ...
    title             VARCHAR(200) NOT NULL,
    body              VARCHAR(1000),
    link_path         VARCHAR(300),                   -- 프론트 라우트 '/requests/12'
    related_request_id BIGINT      REFERENCES test_requests(id) ON DELETE SET NULL,
    read_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user ON notifications(user_id, read_at, created_at DESC);

-- V6: 사양 사전 및 예약·정비 경합 검증
CREATE FUNCTION validate_equipment_spec() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE d spec_definitions;
BEGIN
    SELECT * INTO d FROM spec_definitions WHERE spec_key = NEW.spec_key;
    IF NOT FOUND THEN RETURN NEW; END IF; -- FK가 알 수 없는 키를 거절한다.
    IF NEW.unit IS DISTINCT FROM d.base_unit
       OR (d.value_type = 'NUMBER' AND NEW.value_num IS NULL)
       OR (d.value_type = 'RANGE' AND (NEW.min_num IS NULL OR NEW.max_num IS NULL))
       OR (d.value_type = 'TEXT' AND NEW.value_text IS NULL) THEN
        RAISE EXCEPTION '사양 사전의 값 형식/기준 단위와 일치하지 않습니다.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION guard_equipment_schedule() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM id FROM equipment WHERE id = NEW.equipment_id FOR UPDATE;
    IF TG_TABLE_NAME = 'reservations' THEN
        IF NEW.status = 'CONFIRMED' AND EXISTS (
            SELECT 1 FROM equipment_downtimes d WHERE d.equipment_id=NEW.equipment_id
                AND d.start_at<NEW.end_at AND d.end_at>NEW.start_at) THEN
            RAISE EXCEPTION '확정 예약과 정비 일정이 겹칩니다.' USING ERRCODE='23P01';
        END IF;
    ELSIF EXISTS (SELECT 1 FROM reservations r WHERE r.equipment_id=NEW.equipment_id
            AND r.status='CONFIRMED' AND r.start_at<NEW.end_at AND r.end_at>NEW.start_at) THEN
        RAISE EXCEPTION '정비 일정과 확정 예약이 겹칩니다.' USING ERRCODE='23P01';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tr_validate_equipment_spec BEFORE INSERT OR UPDATE ON equipment_specs
    FOR EACH ROW EXECUTE FUNCTION validate_equipment_spec();

CREATE TRIGGER tr_reservation_downtime BEFORE INSERT OR UPDATE ON reservations
    FOR EACH ROW EXECUTE FUNCTION guard_equipment_schedule();

CREATE TRIGGER tr_downtime_reservation BEFORE INSERT OR UPDATE ON equipment_downtimes
    FOR EACH ROW EXECUTE FUNCTION guard_equipment_schedule();
