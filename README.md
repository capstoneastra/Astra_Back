# ASTRA — 우주항공 시험장비 공동활용 플랫폼 (Backend)

> 장비 이름을 몰라도, 시험 조건만으로 맞는 시험장비를 찾고 의뢰까지 끝내는 플랫폼
>
> AI 디자인 캡스톤 7조 · 백엔드 저장소

## 목차
1. [프로젝트 방향](#1-프로젝트-방향)
2. [핵심 흐름](#2-핵심-흐름)
3. [설계 원칙](#3-설계-원칙)
4. [기능과 담당](#4-기능과-담당)
5. [ERD](#5-erd)
6. [의뢰 상태 흐름](#6-의뢰-상태-흐름)
7. [기술 스택](#7-기술-스택)
8. [실행하기](#8-실행하기)
9. [코드 구조](#9-코드-구조)
10. [개발 규칙](#10-개발-규칙)
11. [문서](#11-문서)

---

## 1. 프로젝트 방향

### 해결하려는 문제
- **장비 정보가 흩어져 있다.** 우주항공 시험장비는 테크노파크, 연구원, 대학 센터 등 여러 기관에 나뉘어 있고, 공개된 정보의 형식도 기관마다 다릅니다.
- **기업은 장비 이름보다 시험 조건을 안다.** "시료 15kg, 20~2000Hz 진동시험"처럼 조건은 알아도 어떤 장비가 맞는지는 모르는 경우가 많습니다.
- **의뢰 과정에서 정보와 상태가 엇갈린다.** 접수 → 보완 요청 → 재제출 → 일정 확정을 거치는 동안 누가 무엇을 고쳤는지 추적하기 어렵습니다.

### ASTRA가 하는 일
지역 시험장비를 한곳에 모으고, **AI 상담으로 시험 조건을 정리 → 조건에 맞는 장비를 근거와 함께 비교 → 의뢰서 초안 작성 → 기관 검토·보완·예약**까지 하나의 흐름으로 잇습니다.

### 사용자

| 역할 | 하는 일 |
|---|---|
| **기업** (`COMPANY_USER`) | AI 상담으로 조건 정리, 장비 검색·비교, 시험 의뢰, 보완 자료 제출 |
| **기관 담당자** (`INSTITUTION_MANAGER`) | 소속 기관 장비 정보 등록·갱신, 들어온 의뢰 검토, 보완 요청, 예약 확정 |
| **플랫폼 관리자** (`ADMIN`) | 전체 권한(마스터), 회원·기관 승인, 초기 데이터 관리 |

로그인하지 않은 사용자는 회원가입·로그인 외에는 아무것도 볼 수 없게 설계했습니다. (JWT 로그인 적용 후 활성화, [현재 상태](#현재-상태) 참고)

### 데이터 현황
- 공개자료로 수집한 **9개 기관, 장비 71대**를 초기 데이터로 넣습니다.
- 장비 상세 사양은 대부분 공개되어 있지 않아 **시연용 가상 사양(MOCK)** 을 쓰고, 화면에 "시연용 데이터"로 표시합니다. 실제 사양은 기관 담당자가 등록하면 그 값을 우선 사용합니다.

---

## 2. 핵심 흐름

```mermaid
flowchart LR
    subgraph COMPANY["기업"]
        A["AI 상담<br/>시험 목적·조건 입력"] --> B["조건 정리<br/>확인 / 미확인 / 누락 구분"]
        B --> C["장비 매칭·비교<br/>조건별 충족 여부와 근거"]
        C --> D["의뢰서 초안<br/>AI 작성 → 사용자 수정·승인"]
        D --> E["의뢰 제출"]
        G["정보 보완<br/>재제출"]
    end
    subgraph INSTITUTION["시험기관"]
        F["기술 검토"]
        H["일정 협의·예약 확정"]
    end
    E --> F
    F -- "보완 요청" --> G
    G --> F
    F -- "검토 통과" --> H
```

버튼 하나로 **조건 분석 → 장비 검색 → 적합성 비교 → 의뢰서 초안**까지 자동으로 진행하는 기능(AI 자동 추진)도 있습니다. 단, 제출은 항상 사용자가 직접 확인한 뒤에 합니다.

---

## 3. 설계 원칙

| 원칙 | 구현 방식 |
|---|---|
| **AI는 돕고, 판단은 기관이 한다** | 매칭 결과에는 항상 "최종 시험 가능 여부는 기관 검토로 확정" 안내. AI 의뢰서는 초안(DRAFT)까지만 만들고 자동 제출하지 않음 |
| **매칭은 규칙으로, 설명은 AI로** | 조건-사양 비교는 `test_condition_rules`의 규칙(범위 포함, 최대값 이하 등)으로 코드가 판정. LLM은 문장에서 조건을 뽑고 추천 이유를 설명하는 데만 사용 → 결과가 매번 같고 근거를 보여줄 수 있음 |
| **모르는 건 모른다고 표시한다** | 조건마다 `CONFIRMED / UNCONFIRMED / MISSING`, 매칭 결과마다 `MET / NOT_MET / UNKNOWN` |
| **누가 무엇을 바꿨는지 남긴다** | 의뢰의 현재 상태(`test_requests`)와 제출본 이력(`test_request_revisions`)을 분리하고, 상태 변경은 모두 `request_status_history`에 기록 |
| **장비 정보의 주인은 기관이다** | 초기 데이터는 공개자료로 넣고 "기관 미확인"으로 표시. 이후 등록·갱신은 기관 담당자가 직접 하고, 확인하면 `INSTITUTION_VERIFIED` |
| **시연 데이터는 시연 데이터라고 말한다** | 가상 사양은 `source_type = MOCK`, 가상 시뮬레이션은 결과마다 DEMO 경고. 실제 장비 성능처럼 표시하지 않음 |
| **중요한 규칙은 DB가 지킨다** | 같은 장비의 확정 예약 시간 겹침 금지, 의뢰당 확정 예약 1개, 의뢰당 답변 대기 보완 요청 1개를 DB 제약조건으로 강제 (동시에 요청해도 하나만 성공) |

---

## 4. 기능과 담당

| # | 기능 | 패키지 | 담당 |
|---|---|---|---|
| 1 | 장비 통합 검색 | `equipment` | BE-A |
| 2 | 장비 상세 정보 (사양, 보유기관, 이용조건, 가동률, 정보 갱신일) | `equipment` | BE-A |
| 3 | 시험항목·자연어 검색 | `testtype`, `equipment` | BE-A |
| 4 | 시험 정보 안내 (가능 기관, 예상 비용, 소요기간) | `testtype` | BE-A |
| 5 | AI 챗봇 상담 | `chat`, `llm` | BE-C |
| 6 | AI 시험조건 분석 (누락 정보 확인) | `requirement` | BE-C |
| 7 | AI 장비 매칭·비교 | `matching` | BE-C |
| 8 | AI 시험 의뢰서 초안 (생성은 `llm`, 저장은 `TestRequestService.createDraft`) | `llm`, `request` | BE-C (생성), BE-B (저장) |
| 9 | 온라인 시험 의뢰 | `request` | BE-B |
| 10 | 기관 검토·정보 보완 | `review` | BE-B |
| 11 | 시험 예약 검토·확정 | `reservation` | BE-B |
| 12 | 중복 장비 조회 | `equipment` | BE-A |
| 13 | AI 자동 추진 | `aipipeline` | BE-C |
| 14 | 메인 대시보드 | `dashboard`, `notification` | BE-A, BE-B |
| 15 | 가상 시뮬레이션 (시연용) | `simulation` | BE-B |
| - | 회원가입·로그인·권한, 관리자 승인, 기관 장비 관리 | `auth`, `user`, `organization`, `admin`, `equipment` | BE-A |

API 90개의 상세 명세와 우선순위(P0 시연 필수 / P1 / P2)는 [`docs/api-spec.md`](docs/api-spec.md), 담당자별 할 일은 [`docs/team-guide.md`](docs/team-guide.md) 5번에 있습니다.

---

## 5. ERD

테이블 27개. 아래 그림은 `docs/design/schema.sql`에서 자동 생성했습니다. 도메인별 상세는 펼쳐서 보세요 (`created_at`, `updated_at` 컬럼은 생략).

<!-- ERD:START (python docs/design/tools/schema_to_mermaid.py 출력으로 교체) -->

#### 전체 관계도

```mermaid
erDiagram
    organizations |o--o{ users : ""
    users ||--o{ refresh_tokens : ""
    test_types ||--o{ test_condition_rules : ""
    organizations ||--o{ equipment : ""
    users |o--o{ equipment : ""
    equipment ||--o{ equipment_specs : ""
    equipment ||--o{ equipment_change_logs : ""
    users ||--o{ equipment_change_logs : ""
    equipment ||--o{ equipment_test_types : ""
    test_types ||--o{ equipment_test_types : ""
    users ||--o{ chat_sessions : ""
    chat_sessions ||--o{ chat_messages : ""
    users ||--o{ test_requirements : ""
    chat_sessions |o--o{ test_requirements : ""
    test_types |o--o{ test_requirements : ""
    test_requirements ||--o{ requirement_conditions : ""
    test_requirements ||--o{ match_runs : ""
    users |o--o{ match_runs : ""
    match_runs ||--o{ match_results : ""
    equipment ||--o{ match_results : ""
    match_results ||--o{ match_condition_checks : ""
    users ||--o{ test_requests : ""
    organizations |o--o{ test_requests : ""
    equipment ||--o{ test_requests : ""
    test_requirements |o--o{ test_requests : ""
    match_results |o--o{ test_requests : ""
    test_requests ||--o{ test_request_revisions : ""
    users ||--o{ test_request_revisions : ""
    test_requests ||--o{ request_status_history : ""
    users ||--o{ request_status_history : ""
    test_requests ||--o{ supplement_requests : ""
    users ||--o{ supplement_requests : ""
    test_requests ||--o{ attachments : ""
    users ||--o{ attachments : ""
    test_requests ||--o{ reservations : ""
    equipment ||--o{ reservations : ""
    users ||--o{ reservations : ""
    users ||--o{ ai_pipeline_runs : ""
    chat_sessions |o--o{ ai_pipeline_runs : ""
    test_requirements |o--o{ ai_pipeline_runs : ""
    match_runs |o--o{ ai_pipeline_runs : ""
    test_requests |o--o{ ai_pipeline_runs : ""
    equipment ||--o{ simulation_presets : ""
    simulation_models ||--o{ simulation_presets : ""
    simulation_presets |o--o{ simulation_runs : ""
    equipment ||--o{ simulation_runs : ""
    users |o--o{ simulation_runs : ""
    test_requirements |o--o{ simulation_runs : ""
    simulation_models ||--o{ simulation_runs : ""
    users ||--o{ notifications : ""
    test_requests |o--o{ notifications : ""
```

<details>
<summary><b>사용자 · 조직</b> (4개 테이블)</summary>

```mermaid
erDiagram
    organizations {
        bigint id PK
        varchar org_type "COMPANY / INSTITUTION / ADMIN"
        varchar name
        varchar parent_name
        varchar business_reg_no
        varchar region
        varchar address
        varchar phone
        varchar email
        varchar homepage_url
        boolean is_verified
    }
    users {
        bigint id PK
        bigint organization_id FK
        varchar email UK
        varchar password_hash
        varchar name
        varchar phone
        varchar role "COMPANY_USER / INSTITUTION_MANAGER / ADMIN"
        varchar status "ACTIVE / PENDING / REJECTED / SUSPENDED"
        bigint approved_by FK
        timestamptz approved_at
        varchar reject_reason
        timestamptz last_login_at
    }
    refresh_tokens {
        bigint id PK
        bigint user_id FK
        varchar token_hash UK
        timestamptz expires_at
        timestamptz revoked_at
    }
    notifications {
        bigint id PK
        bigint user_id FK
        varchar type
        varchar title
        varchar body
        varchar link_path
        bigint related_request_id FK
        timestamptz read_at
    }
    test_requests {
        bigint id PK
    }
    organizations |o--o{ users : "organization_id"
    users |o--o{ users : "approved_by"
    users ||--o{ refresh_tokens : "user_id"
    users ||--o{ notifications : "user_id"
    test_requests |o--o{ notifications : "related_request_id"
```

</details>

<details>
<summary><b>장비 · 시험유형</b> (6개 테이블)</summary>

```mermaid
erDiagram
    equipment {
        bigint id PK
        varchar external_id UK
        bigint institution_id FK
        varchar name
        varchar model
        varchar manufacturer
        varchar category
        varchar equipment_type "MEASUREMENT / MANUFACTURING / TEST / SOFTWARE / SUPPORT"
        varchar region
        text description
        varchar image_url
        varchar access_scope "EXTERNAL_ALLOWED / INTERNAL_ONLY / UNKNOWN"
        varchar fee_text
        int fee_amount
        varchar fee_unit "HOUR / DAY / CASE / SAMPLE / NEGOTIABLE"
        varchar duration_text
        numeric utilization_rate
        varchar availability "AVAILABLE / BUSY / MAINTENANCE / UNAVAILABLE / UNKNOWN"
        varchar source_type "WEB / PDF / INSTITUTION / MANUAL"
        varchar source_url
        varchar source_file
        int source_page
        varchar verification_status "NEEDS_MANUAL_REVIEW / LISTING_CONFIRMED / INSTITUTION_VERIFIED"
        varchar spec_verification_status "NOT_VERIFIED / UNVERIFIED / VERIFIED"
        varchar aerospace_relevance
        boolean is_mock
        varchar simulation_capability "CALCULATED_DEMO / RULE_BASED_DEMO / VISUALIZATION_DEMO / WORKFLOW_DEMO"
        timestamptz info_updated_at
        boolean is_active
        bigint created_by FK
        bigint updated_by FK
    }
    equipment_specs {
        bigint id PK
        bigint equipment_id FK
        varchar spec_key
        varchar label
        numeric value_num
        numeric min_num
        numeric max_num
        varchar value_text
        varchar unit
        varchar source_type "REAL / MOCK"
        varchar verification_status
        varchar usage_scope "GENERAL / DEMO_ONLY"
    }
    equipment_change_logs {
        bigint id PK
        bigint equipment_id FK
        bigint changed_by FK
        varchar action "CREATE / UPDATE / SPECS_UPDATE / TEST_TYPES_UPDATE / CONFIRM / DEACTIVATE / TRANSFER"
        jsonb changes
    }
    test_types {
        varchar code PK
        varchar name_ko
        text description
        text_array keywords
        int display_order
    }
    equipment_test_types {
        bigint equipment_id PK, FK
        varchar test_type_code PK, FK
        int est_cost_min
        int est_cost_max
        int est_duration_days
        varchar note
    }
    test_condition_rules {
        varchar test_type_code PK, FK
        varchar condition_key PK
        varchar label
        varchar value_type "NUMBER / RANGE / TEXT"
        varchar unit
        boolean is_required
        varchar spec_key
        varchar comparator "RANGE_WITHIN / MAX_LIMIT / MIN_LIMIT / NONE"
        int display_order
    }
    organizations {
        bigint id PK
    }
    users {
        bigint id PK
    }
    organizations ||--o{ equipment : "institution_id"
    users |o--o{ equipment : "created_by"
    users |o--o{ equipment : "updated_by"
    equipment ||--o{ equipment_specs : "equipment_id"
    equipment ||--o{ equipment_change_logs : "equipment_id"
    users ||--o{ equipment_change_logs : "changed_by"
    equipment ||--o{ equipment_test_types : "equipment_id"
    test_types ||--o{ equipment_test_types : "test_type_code"
    test_types ||--o{ test_condition_rules : "test_type_code"
```

</details>

<details>
<summary><b>AI 상담 · 시험조건 · 매칭 · 자동 추진</b> (8개 테이블)</summary>

```mermaid
erDiagram
    chat_sessions {
        bigint id PK
        bigint user_id FK
        varchar title
        varchar status "ACTIVE / CLOSED"
    }
    chat_messages {
        bigint id PK
        bigint session_id FK
        varchar role "USER / ASSISTANT / SYSTEM"
        text content
        jsonb metadata
    }
    test_requirements {
        bigint id PK
        bigint user_id FK
        bigint chat_session_id FK
        varchar test_type_code FK
        varchar title
        text purpose
        varchar sample_name
        int sample_quantity
        varchar status "DRAFT / CONFIRMED / ARCHIVED"
    }
    requirement_conditions {
        bigint id PK
        bigint requirement_id FK
        varchar condition_key
        varchar label
        numeric value_num
        numeric min_num
        numeric max_num
        varchar value_text
        varchar unit
        varchar status "CONFIRMED / UNCONFIRMED / MISSING"
        varchar source "USER / AI_EXTRACTED / INSTITUTION"
        numeric confidence
        varchar note
    }
    match_runs {
        bigint id PK
        bigint requirement_id FK
        varchar algorithm_version
        jsonb requirement_snapshot
        bigint created_by FK
    }
    match_results {
        bigint id PK
        bigint match_run_id FK
        bigint equipment_id FK
        int rank
        numeric score
        varchar verdict "SUITABLE / PARTIAL / UNSUITABLE / UNKNOWN"
        text reason
        boolean used_mock_data
    }
    match_condition_checks {
        bigint id PK
        bigint match_result_id FK
        varchar condition_key
        varchar required_text
        varchar equipment_text
        varchar result "MET / NOT_MET / UNKNOWN"
        varchar note
    }
    ai_pipeline_runs {
        bigint id PK
        bigint user_id FK
        bigint chat_session_id FK
        text input_text
        bigint requirement_id FK
        bigint match_run_id FK
        bigint draft_request_id FK
        varchar status "PENDING / RUNNING / WAITING_USER / SUCCEEDED / FAILED / CANCELED"
        varchar current_step "ANALYZE / SEARCH / MATCH / DRAFT / DONE"
        jsonb steps_log
        text error_message
        timestamptz started_at
        timestamptz finished_at
    }
    equipment {
        bigint id PK
    }
    test_requests {
        bigint id PK
    }
    test_types {
        varchar code PK
    }
    users {
        bigint id PK
    }
    users ||--o{ chat_sessions : "user_id"
    chat_sessions ||--o{ chat_messages : "session_id"
    users ||--o{ test_requirements : "user_id"
    chat_sessions |o--o{ test_requirements : "chat_session_id"
    test_types |o--o{ test_requirements : "test_type_code"
    test_requirements ||--o{ requirement_conditions : "requirement_id"
    test_requirements ||--o{ match_runs : "requirement_id"
    users |o--o{ match_runs : "created_by"
    match_runs ||--o{ match_results : "match_run_id"
    equipment ||--o{ match_results : "equipment_id"
    match_results ||--o{ match_condition_checks : "match_result_id"
    users ||--o{ ai_pipeline_runs : "user_id"
    chat_sessions |o--o{ ai_pipeline_runs : "chat_session_id"
    test_requirements |o--o{ ai_pipeline_runs : "requirement_id"
    match_runs |o--o{ ai_pipeline_runs : "match_run_id"
    test_requests |o--o{ ai_pipeline_runs : "draft_request_id"
```

</details>

<details>
<summary><b>시험 의뢰 · 검토 · 보완 · 예약</b> (6개 테이블)</summary>

```mermaid
erDiagram
    test_requests {
        bigint id PK
        varchar request_no UK
        bigint requester_id FK
        bigint company_org_id FK
        bigint institution_id FK
        bigint equipment_id FK
        bigint requirement_id FK
        bigint match_result_id FK
        bigint assignee_id FK
        varchar title
        varchar status "DRAFT / SUBMITTED / UNDER_REVIEW / SUPPLEMENT_REQUESTED / RESUBMITTED / SCHEDULING / CONFIRMED / COMPLETED / REJECTED / CANCELED"
        int current_revision_no
        date desired_start_date
        date desired_end_date
        timestamptz submitted_at
        timestamptz closed_at
        int version
    }
    test_request_revisions {
        bigint id PK
        bigint request_id FK
        int revision_no
        jsonb content
        boolean is_ai_generated
        boolean is_submitted
        varchar change_summary
        bigint author_id FK
        timestamptz submitted_at
    }
    request_status_history {
        bigint id PK
        bigint request_id FK
        varchar from_status
        varchar to_status
        bigint changed_by FK
        varchar reason
    }
    supplement_requests {
        bigint id PK
        bigint request_id FK
        int based_revision_no
        bigint requested_by FK
        text message
        jsonb items
        date due_date
        varchar status "OPEN / RESPONDED / CLOSED"
        int response_revision_no
        text response_message
        timestamptz responded_at
    }
    attachments {
        bigint id PK
        bigint request_id FK
        int revision_no
        varchar file_name
        varchar storage_key
        varchar mime_type
        bigint size_bytes
        bigint uploaded_by FK
    }
    reservations {
        bigint id PK
        bigint request_id FK
        bigint equipment_id FK
        timestamptz start_at
        timestamptz end_at
        varchar status "PROPOSED / COUNTER_PROPOSED / CONFIRMED / CANCELED"
        bigint proposed_by FK
        bigint confirmed_by FK
        timestamptz confirmed_at
        varchar external_system
        varchar external_ref
        varchar note
    }
    equipment {
        bigint id PK
    }
    match_results {
        bigint id PK
    }
    organizations {
        bigint id PK
    }
    test_requirements {
        bigint id PK
    }
    users {
        bigint id PK
    }
    users ||--o{ test_requests : "requester_id"
    organizations |o--o{ test_requests : "company_org_id"
    organizations ||--o{ test_requests : "institution_id"
    equipment ||--o{ test_requests : "equipment_id"
    test_requirements |o--o{ test_requests : "requirement_id"
    match_results |o--o{ test_requests : "match_result_id"
    users |o--o{ test_requests : "assignee_id"
    test_requests ||--o{ test_request_revisions : "request_id"
    users ||--o{ test_request_revisions : "author_id"
    test_requests ||--o{ request_status_history : "request_id"
    users ||--o{ request_status_history : "changed_by"
    test_requests ||--o{ supplement_requests : "request_id"
    users ||--o{ supplement_requests : "requested_by"
    test_requests ||--o{ attachments : "request_id"
    users ||--o{ attachments : "uploaded_by"
    test_requests ||--o{ reservations : "request_id"
    equipment ||--o{ reservations : "equipment_id"
    users ||--o{ reservations : "proposed_by"
    users |o--o{ reservations : "confirmed_by"
```

</details>

<details>
<summary><b>가상 시뮬레이션</b> (3개 테이블)</summary>

```mermaid
erDiagram
    simulation_models {
        varchar model_type PK
        text description
        text_array required_input_fields
        text limitation
        varchar capability "CALCULATED_DEMO / RULE_BASED_DEMO / VISUALIZATION_DEMO / WORKFLOW_DEMO"
        varchar model_version
        boolean is_implemented
    }
    simulation_presets {
        varchar preset_id PK
        bigint equipment_id FK
        varchar model_type FK
        varchar simulation_type
        jsonb input_parameters
        jsonb specs_snapshot
        jsonb assumptions
        boolean is_mock
    }
    simulation_runs {
        bigint id PK
        varchar preset_id FK
        bigint equipment_id FK
        bigint user_id FK
        bigint requirement_id FK
        varchar model_type FK
        varchar model_version
        jsonb input_parameters
        jsonb output
        varchar status "NOT_EXECUTED / RUNNING / SUCCEEDED / FAILED"
        boolean is_mock
        text warning
    }
    equipment {
        bigint id PK
    }
    test_requirements {
        bigint id PK
    }
    users {
        bigint id PK
    }
    equipment ||--o{ simulation_presets : "equipment_id"
    simulation_models ||--o{ simulation_presets : "model_type"
    simulation_presets |o--o{ simulation_runs : "preset_id"
    equipment ||--o{ simulation_runs : "equipment_id"
    users |o--o{ simulation_runs : "user_id"
    test_requirements |o--o{ simulation_runs : "requirement_id"
    simulation_models ||--o{ simulation_runs : "model_type"
```

</details>

<!-- ERD:END -->

- 전체 컬럼·제약조건의 기준은 [`docs/design/schema.sql`](docs/design/schema.sql)입니다.
- dbdiagram.io용 ERD: [`docs/design/erd.dbml`](docs/design/erd.dbml)
- 스키마를 바꾸면 `python docs/design/tools/schema_to_mermaid.py`로 위 그림을 다시 만듭니다.

---

## 6. 의뢰 상태 흐름

```mermaid
stateDiagram-v2
    direction LR
    [*] --> DRAFT
    DRAFT --> SUBMITTED : 제출 (기업)
    SUBMITTED --> UNDER_REVIEW : 검토 시작 (기관)
    UNDER_REVIEW --> SUPPLEMENT_REQUESTED : 보완 요청 (기관)
    SUPPLEMENT_REQUESTED --> RESUBMITTED : 보완 제출 (기업)
    RESUBMITTED --> UNDER_REVIEW : 검토 시작 (기관)
    UNDER_REVIEW --> SCHEDULING : 검토 통과 (기관)
    UNDER_REVIEW --> REJECTED : 반려 (기관)
    SCHEDULING --> CONFIRMED : 예약 확정 (기관)
    CONFIRMED --> COMPLETED : 시험 완료 (기관)
    REJECTED --> [*]
    COMPLETED --> [*]
```

`DRAFT`부터 `SCHEDULING`까지는 기업이 언제든 취소(`CANCELED`)할 수 있습니다. 상태 변경은 모두 상태 갱신 + 이력 저장 + 상대방 알림을 한 트랜잭션으로 처리합니다.

---

## 7. 기술 스택

| 구분 | 사용 |
|---|---|
| 언어 / 프레임워크 | Java 21 (LTS), Spring Boot 4.1.2 |
| 빌드 | Gradle 9.8.2 (Wrapper) |
| DB | PostgreSQL 16, Flyway, Spring Data JPA |
| 보안 | Spring Security 7, JWT (jjwt 0.13.0) |
| API 문서 | springdoc-openapi 3.2.0 (Swagger UI) |
| AI | Anthropic Java SDK 3.6.0 (`claude-opus-5-5`) |
| 테스트 | JUnit 5, Testcontainers (PostgreSQL 16) |
| CI | GitHub Actions (PR·`main` 푸시마다 빌드 + 테스트) |

---

## 8. 실행하기

**준비물**: JDK 21, Docker Desktop (켜져 있어야 함)

```bash
git clone https://github.com/capstoneastra/Astra_Back.git
cd Astra_Back
cp .env.example .env        # 필요한 값 채우기 (DB 는 기본값 그대로 두면 됨)
./gradlew bootRun           # Windows: gradlew.bat bootRun
```

- `bootRun`을 하면 `compose.yaml`의 PostgreSQL 16이 자동으로 뜨고, Flyway가 `V1__init.sql`(테이블)과 `V2__seed.sql`(초기 데이터)을 적용합니다.
- Swagger UI: http://localhost:8080/swagger-ui.html
- 헬스체크: http://localhost:8080/actuator/health
- IntelliJ: **Project SDK와 Gradle JVM을 모두 21**로 맞춘 뒤 `AstraApplication`을 실행합니다.
- DB를 처음부터 다시 만들려면: `docker compose down -v` 후 다시 실행합니다.

### 테스트

```bash
./gradlew test
```

Testcontainers가 테스트 전용 PostgreSQL 16을 띄워 실제 DB로 검증합니다 (Docker 필요).

| 테스트 | 확인하는 것 |
|---|---|
| `DatabaseSchemaTest` | 마이그레이션 적용, 초기 데이터 개수, 조건 규칙의 사양 키 존재, 중복 예약 방지, 의뢰당 확정 예약 1개, 의뢰당 답변 대기 보완 요청 1개 |
| `ApiConventionTest` | 공통 실패 응답 형식, Swagger·헬스체크 접근 |
| `SecurityRequireLoginTest` | 로그인 필수 모드에서 비로그인 호출 시 `401 AUTH_REQUIRED` |

---

## 9. 코드 구조

```
src/main/java/kr/astra/
├── global/         공통: 응답 형식, 에러 코드, 예외 처리, 보안·Swagger 설정, BaseTimeEntity   ← BE-A (모두 사용)
├── auth/ user/ organization/ admin/ equipment/ testtype/ dashboard/                   ← BE-A
├── request/ review/ reservation/ notification/ simulation/                           ← BE-B
└── chat/ requirement/ matching/ aipipeline/ llm/                                     ← BE-C
```

- 각 도메인은 `controller / service / repository / entity / dto`로 나뉩니다. 각 패키지의 `package-info.java`에 담당자와 역할이 적혀 있습니다.
- `*/entity/`에는 DB의 CHECK 제약과 값이 1:1로 맞는 **enum 29개**가 미리 들어 있습니다. 새로 만들지 말고 이걸 씁니다.
- 담당자끼리 주고받는 **내부 인터페이스**는 이름과 입출력만 정의되어 있고, 구현은 각 담당자가 합니다.

| 인터페이스 | 구현 | 사용 |
|---|---|---|
| `equipment.service.EquipmentQueryService` | BE-A | BE-C (매칭) |
| `requirement.service.RequirementService` | BE-C | BE-B (의뢰서) |
| `request.service.TestRequestService` | BE-B | BE-C (AI 초안·자동 추진) |
| `notification.service.NotificationService` | BE-B | 전원 |
| `global.security.CurrentUser` | BE-A (JWT 필터에서 생성) | 전원 |

### 현재 상태
- ✅ 프로젝트 뼈대, 공통 모듈, DB 스키마·초기 데이터, 테스트, CI
- ⏳ 각 도메인의 Entity·API 구현 (담당자별 진행, [`docs/team-guide.md`](docs/team-guide.md) 주차별 계획 참고)
- ⏳ JWT 로그인 (BE-A 2주차). 그 전까지는 `astra.security.require-login=false`라 로그인 없이 모든 API를 호출할 수 있습니다.

---

## 10. 개발 규칙

자세한 내용은 [`docs/team-guide.md`](docs/team-guide.md)를 봅니다.

- **브랜치**: `main` 하나만 씁니다. `main`에서 `feature/#이슈번호-작업내용`으로 따서 작업하고 `main`으로 PR을 올립니다. CI 통과 + 리뷰 1명 승인 후 Squash merge합니다. 발표·중간점검 버전은 태그(`v0.1-midterm` 등)로 표시합니다.
- **커밋**: `타입: 내용` (예: `feat: 장비 목록 검색 API 추가`, `fix:`, `refactor:`, `docs:`, `chore:`)
- **API 경로**: 컨트롤러에는 [`docs/api-spec.md`](docs/api-spec.md)의 경로를 그대로 씁니다. `/api/v1`은 자동으로 붙습니다.
- **응답**: `ApiResponse.ok(data)`로 감싸서 반환하고, 실패는 `throw new BusinessException(ErrorCode.XXX)`로 던집니다. 에러 코드를 추가하면 api-spec.md §0-3도 같이 수정합니다.
- **DB 변경**: 이미 병합된 마이그레이션 파일은 절대 수정하지 않습니다. 새 파일 `V3__설명.sql`을 추가하고, 번호는 채팅으로 먼저 선점합니다. `docs/design/schema.sql`과 README의 ERD도 같은 PR에서 갱신합니다.
- **Entity**: `ddl-auto=validate`라서 Entity와 테이블이 다르면 서버가 뜨지 않습니다. 컬럼은 `docs/design/schema.sql`과 정확히 맞춥니다.
- **제약 위반 처리**: 예약 겹침 같은 DB 제약 위반은 `saveAndFlush()` 후 `DbConstraints.violates(e, ...)`로 구분해서 409로 바꿉니다.
- **비밀값**: `.env`, API 키, 비밀번호는 절대 커밋하지 않습니다.

---

## 11. 문서

| 문서 | 내용 |
|---|---|
| [`docs/api-spec.md`](docs/api-spec.md) | API 명세 90개, 에러 코드, 매칭 규칙, 의뢰 상태 전이표 |
| [`docs/team-guide.md`](docs/team-guide.md) | 기술 스택, 협업 규칙, 담당자별 API·주차별 계획 |
| [`docs/design/schema.sql`](docs/design/schema.sql) | 전체 테이블 설계 (27개) |
| [`docs/design/erd.dbml`](docs/design/erd.dbml) | ERD (dbdiagram.io에 붙여넣기) |
| [`docs/design/seed.sql`](docs/design/seed.sql) | 초기 데이터 (`docs/design/tools/json_to_seed.py`로 생성) |
