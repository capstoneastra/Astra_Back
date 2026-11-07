# ASTRA API 명세서 v0.1 (초안)

> 기준: REST + JSON, Base URL `/api/v1`, 인증 `Authorization: Bearer <accessToken>` (JWT)
> DB 기준 문서: `schema.sql` / ERD: `erd.dbml`
> 표기: 🔓 비로그인 허용(`/auth/**`만) · `로그인` 로그인한 모든 사용자 · 🏢 기업(COMPANY_USER) · 🏛 기관 담당자(INSTITUTION_MANAGER) · 👑 플랫폼 관리자(ADMIN)
> 👑 관리자는 **마스터 권한**이라 모든 API를 호출할 수 있습니다. 그래서 표에는 관리자 전용 API에만 👑를 표시합니다.

---

## 0. 공통 규약

### 0-1. 응답 형식
```json
// 성공
{ "success": true, "data": { ... } }

// 목록 (페이지네이션)
{ "success": true,
  "data": { "content": [ ... ], "page": 0, "size": 20, "totalElements": 71, "totalPages": 4 } }

// 실패
{ "success": false,
  "error": { "code": "REQUEST_INVALID_STATUS", "message": "보완 요청 상태에서만 재제출할 수 있습니다.", "details": null } }
```

### 0-2. 규칙
| 항목 | 규칙 |
|---|---|
| JSON 필드명 | camelCase (`desiredStartDate`) — DB는 snake_case |
| 날짜/시각 | `2026-11-20` / ISO-8601 `2026-11-05T09:00:00+09:00` |
| 페이지네이션 | `?page=0&size=20&sort=createdAt,desc` (0부터 시작) |
| enum | 대문자 문자열 그대로 (`UNDER_REVIEW`) — 화면 한글 라벨은 프론트에서 매핑 |
| ID | 숫자 `id` 사용. 장비는 `externalId`도 함께 응답 (초기 적재 `ASTRA-CAND-0001`, 기관 신규 등록 `ASTRA-EQ-0072`) |
| 조건·사양 키 | JSON 필드명(camelCase)과 별개로, 조건·사양을 가리키는 **키 값**은 단위 없는 snake_case. 단위는 `unit`에 따로 (`{ "key": "sample_mass", "value": 15, "unit": "kg" }`). 시험유형별 키 목록은 `GET /test-types/{code}`의 `conditionRules` |
| MOCK 표시 | 시연용 데이터가 섞인 응답엔 `isMock`/`sourceType` 필드를 반드시 포함, 프론트는 "DEMO" 배지 표시 |
| 동시성 | 상태 변경 요청은 `version` 필드를 보내고, 불일치 시 `409 CONFLICT` |

### 0-3. HTTP 상태 / 에러 코드
| HTTP | code 예시 | 상황 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 입력값 검증 실패 (`details`에 필드별 사유) |
| 401 | `AUTH_REQUIRED`, `TOKEN_EXPIRED`, `INVALID_CREDENTIALS` | 미인증·잘못된 토큰 / access 토큰 만료 → refresh 시도 / 로그인 시 이메일 또는 비밀번호 불일치 |
| 403 | `FORBIDDEN`, `ACCOUNT_NOT_ACTIVE` | 역할·소유권 불일치 (다른 기관 의뢰 조회 등) / 승인 대기·거절·정지 계정 로그인 |
| 404 | `NOT_FOUND`, `EQUIPMENT_NOT_FOUND`, `REQUEST_NOT_FOUND` | 없는 주소 / 없는 장비 / 없는 의뢰 |
| 405 | `METHOD_NOT_ALLOWED` | 지원하지 않는 HTTP 메서드 |
| 409 | `REQUEST_INVALID_STATUS`, `VERSION_CONFLICT`, `RESERVATION_OVERLAP`, `RESERVATION_ALREADY_CONFIRMED`, `SUPPLEMENT_ALREADY_OPEN` | 상태 전이 불가 / 동시 수정 / 일정 중복 / 이미 확정된 의뢰 / 답변 대기 중인 보완 요청 존재 |
| 409 | `EMAIL_ALREADY_EXISTS`, `ORGANIZATION_ALREADY_EXISTS` | 회원가입: 이미 가입된 이메일 / 같은 유형에 같은 이름의 조직이 이미 있음 (`organizations` UNIQUE(org_type, name)) |
| 500 | `INTERNAL_ERROR` | 처리되지 않은 서버 오류 (서버 로그 확인) |
| 502 | `AI_UPSTREAM_ERROR` | LLM 호출 실패 |

### 0-4. 권한 규칙
| 역할 | 범위 |
|---|---|
| 비로그인 | `/auth/**` (가입·로그인·토큰 재발급·가입용 기관 목록)만 허용. 나머지는 전부 `401 AUTH_REQUIRED`. 프론트는 로그인 페이지 외 모든 라우트에서 로그인 페이지로 이동 |
| 🏢 기업 | 장비·시험정보 조회, 본인 상담/시험조건/의뢰/시뮬레이션만 조회·수정 |
| 🏛 기관 담당자 | 장비·시험정보 조회, **소속 기관 장비 등록·수정**, 소속 기관으로 들어온 의뢰 검토·보완요청·예약 확정 |
| 👑 플랫폼 관리자 | **전체(마스터)**. 모든 조회·수정, 회원·기관 승인, 초기 데이터 적재. 관리자가 대신 처리한 상태 변경도 `request_status_history.changed_by`에 관리자 id로 남음 |

- 소유권 체크: 기관 담당자는 `user.organization_id == equipment.institution_id` (의뢰는 `test_requests.institution_id`) 일 때만 수정 가능, 아니면 `403 FORBIDDEN`
- 상태가 `ACTIVE`가 아닌 계정(PENDING/REJECTED/SUSPENDED)은 로그인 시 `403 ACCOUNT_NOT_ACTIVE` (승인 대기 안내 화면)
- 관리자 계정은 회원가입으로 만들 수 없음. 서버 초기 세팅(seed/환경변수)으로만 생성

---

## 1. 인증 · 사용자 (BE-A)

| Method | URL | 권한 | 설명 |
|---|---|---|---|
| POST | `/auth/signup` | 🔓 | 회원가입 (기업 / 기관 담당자) |
| POST | `/auth/login` | 🔓 | 로그인 → access/refresh 토큰 |
| POST | `/auth/refresh` | 🔓 | 토큰 재발급 |
| GET | `/auth/institutions` | 🔓 | 가입용 기관 목록 (id·이름·지역만) |
| POST | `/auth/logout` | 로그인 | refresh 토큰 폐기 |
| GET | `/users/me` | 로그인 | 내 정보 + 소속 조직 |
| PATCH | `/users/me` | 로그인 | 내 정보 수정 |

**가입 흐름**

| 가입 유형 | 입력 | 가입 직후 상태 | 활성화 |
|---|---|---|---|
| 기업 | 회사명, 사업자번호 → 기업 조직 새로 생성 | `ACTIVE` | 바로 이용 |
| 기관 담당자 (등록된 기관) | 기관 목록에서 선택 `organizationId` | `PENDING` | 관리자가 승인 → `ACTIVE` |
| 기관 담당자 (미등록 기관) | 기관명·주소·연락처 → 기관 조직 생성 (`is_verified=false`) | `PENDING` | 관리자가 기관 승인 + 회원 승인 |
| 플랫폼 관리자 | 가입 불가 | - | 서버 초기 세팅으로 생성 |

> 가입 화면에서 기관을 고르려면 로그인 전에 기관 목록이 필요합니다. 그래서 `GET /auth/institutions`를 `/auth` 아래에 두고 이름·지역만 반환합니다.

```json
// POST /auth/signup (기업)
{ "email": "a@company.com", "password": "****", "name": "홍길동", "phone": "010-0000-0000",
  "role": "COMPANY_USER",
  "organization": { "name": "가상 A기업", "businessRegNo": "123-45-67890" } }

// POST /auth/signup (기관 담당자 - 기존 기관 선택)
{ "email": "kim@ktp.or.kr", "password": "****", "name": "김담당", "phone": "042-000-0000",
  "role": "INSTITUTION_MANAGER", "organizationId": 3 }
// → 201 { "userId": 21, "status": "PENDING" }  관리자 승인 전에는 로그인 시 403 ACCOUNT_NOT_ACTIVE

// POST /auth/signup (기관 담당자 - 미등록 기관 신청: organizationId 대신 organization)
{ "email": "lee@newlab.re.kr", "password": "****", "name": "이담당", "phone": "055-000-0000",
  "role": "INSTITUTION_MANAGER",
  "organization": { "name": "가상 신규 시험기관", "address": "경남 진주시 ...", "phone": "055-000-0000" } }
// → 201 { "userId": 22, "status": "PENDING" }
// 실패: 409 EMAIL_ALREADY_EXISTS / 409 ORGANIZATION_ALREADY_EXISTS / 400 VALIDATION_FAILED (role=ADMIN 포함)

// POST /auth/login { "email": "a@company.com", "password": "****" } → 200
{ "accessToken": "eyJ...", "refreshToken": "eyJ...", "expiresIn": 3600,
  "user": { "id": 1, "name": "홍길동", "role": "COMPANY_USER", "organizationId": 10 } }
// 실패: 401 INVALID_CREDENTIALS / 403 ACCOUNT_NOT_ACTIVE

// POST /auth/refresh { "refreshToken": "eyJ..." } → 200 (로그인과 같은 형식, refresh 토큰도 새로 발급)
// 한 번 쓴 refresh 토큰은 폐기됨. 만료·폐기된 토큰이면 401 AUTH_REQUIRED → 다시 로그인

// POST /auth/logout { "refreshToken": "eyJ..." } → 200 { "success": true }

// GET /auth/institutions → 200 (승인된 기관만, 가나다순)
[ { "id": 5, "name": "경상국립대학교 첨단소재분석지원센터", "region": "진주" }, ... ]

// GET /users/me → 200 (organization 은 소속이 없으면 null)
{ "id": 3, "email": "manager1@astra.demo", "name": "김담당", "phone": "042-000-0001",
  "role": "INSTITUTION_MANAGER", "status": "ACTIVE",
  "organization": { "id": 2, "orgType": "INSTITUTION", "name": "대전테크노파크 우주·양자산업센터", "region": "대전", "isVerified": true },
  "lastLoginAt": "2026-11-07T17:03:04+09:00", "createdAt": "2026-11-07T17:02:51+09:00" }

// PATCH /users/me { "name"?: "김담당", "phone"?: "042-000-0009" } → 200 (GET 과 같은 형식, 보낸 값만 수정)
```

> 시연 계정(V3 seed): `admin@astra.demo`, `company@astra.demo`, `manager1@astra.demo`, `manager2@astra.demo`, `pending@astra.demo`(PENDING). 비밀번호는 팀 채팅으로 공유합니다.

> 토큰: access 토큰은 `Authorization: Bearer` 헤더로 보냅니다(유효 1시간). refresh 토큰은 유효 14일이고, 서버는 원문 대신 해시만 `refresh_tokens.token_hash`에 저장합니다.

---

## 2. 장비 · 검색 · 시험정보 (BE-A) — 기능 1·2·3·4·12

| Method | URL | 권한 | 설명 | 기능 |
|---|---|---|---|---|
| GET | `/equipment` | 로그인 | 장비 통합 검색/목록 | 1 |
| GET | `/equipment/{id}` | 로그인 | 장비 상세 (사양, 이용조건, 가동률, 갱신일) | 2 |
| GET | `/equipment/{id}/similar` | 로그인 | 동일·유사 장비 (같은 시험유형/분류, 이름 유사도) | 12 |
| GET | `/equipment/duplicates` | 로그인 | 지역 내 중복 장비 그룹 목록 `?region=대전&testType=VIBRATION` | 12 |
| POST | `/equipment/compare` | 로그인 | 장비 N개 사양 비교표 `{ "equipmentIds": [1,5,9] }` | 12 |
| GET | `/search` | 로그인 | 시험항목·자연어 통합 검색 `?q=15kg 시료 진동시험` | 3 |
| GET | `/test-types` | 로그인 | 시험유형 목록 | 3 |
| GET | `/test-types/{code}` | 로그인 | 시험 정보 안내: 가능 기관·예상비용·기간·이용조건 + 입력할 조건 목록(`conditionRules`) | 4 |
| GET | `/institutions` | 로그인 | 기관 목록 | |
| GET | `/institutions/{id}` | 로그인 | 기관 상세 + 보유 장비 수 | |

**GET `/equipment` 쿼리**

| 파라미터 | 예시 | 설명 |
|---|---|---|
| `q` | `진동` | 장비명·모델·제조사·시험유형 키워드 (pg_trgm) |
| `region` | `대전` | 복수 가능 `region=대전&region=진주` |
| `institutionId` | `3` | |
| `equipmentType` | `TEST` | MEASUREMENT / MANUFACTURING / TEST / SOFTWARE / SUPPORT |
| `testType` | `VIBRATION` | |
| `availability` | `AVAILABLE` | |
| `externalOnly` | `true` | 외부 이용 가능만 |
| `includeMock` | `true` | 기본 true(시연), 운영 시 false |

```json
// GET /equipment?testType=VIBRATION → content[] 항목  (seed 실제 데이터 ASTRA-CAND-0048)
{ "id": 48, "externalId": "ASTRA-CAND-0048", "name": "진동시험기", "model": "EDS5000LS4-445",
  "institution": { "id": 2, "name": "대전테크노파크 우주·양자산업센터" }, "region": "대전",
  "equipmentType": "TEST", "testTypes": ["VIBRATION"],
  "availability": "UNKNOWN", "utilizationRate": null, "feeText": null,
  "verificationStatus": "LISTING_CONFIRMED", "isMock": true, "infoUpdatedAt": "2026-11-05T00:00:00+09:00" }

// GET /equipment/48 → 위 필드 + 아래
{ ...,
  "manufacturer": "(주)팜테크", "category": null, "description": null,
  "usage": { "accessScope": "UNKNOWN", "feeText": null, "feeAmount": null, "feeUnit": null,
             "durationText": null, "utilizationRate": null, "availability": "UNKNOWN" },
  "specs": [
    { "key": "frequency", "label": "주파수 범위 시연값", "min": 10, "max": 3000, "unit": "Hz",
      "sourceType": "MOCK", "usageScope": "DEMO_ONLY", "verificationStatus": "NOT_VERIFIED" },
    { "key": "max_payload", "label": "최대 시료 질량", "value": 40, "unit": "kg", "sourceType": "MOCK", ... },
    { "key": "max_fixture_mass", "label": "고정구 질량 허용 예시", "value": 15, "unit": "kg", "sourceType": "MOCK", ... },
    { "key": "rated_sine_force", "label": "정현파 가진력 예시", "value": 35, "unit": "kN", "sourceType": "MOCK", ... } ],
  "source": { "type": "WEB", "url": "https://deps.dips.or.kr/mic/community/equipCommonList?comGubun=Universe&manuId=MENU00421",
              "file": "astra_deps_additional_10.json", "page": null },
  "simulation": { "capability": "CALCULATED_DEMO", "presetIds": ["SIM-0048"] } }

// GET /search?q=시료 15kg 20~2000Hz 진동 → 기능 3
{ "interpreted": { "testTypes": ["VIBRATION"],
                   "conditions": [ { "key": "sample_mass", "value": 15, "unit": "kg" },
                                   { "key": "frequency", "min": 20, "max": 2000, "unit": "Hz" } ] },
  "testTypes": [ { "code": "VIBRATION", "nameKo": "진동시험" } ],
  "equipment": [ /* 목록 항목 형식 */ ] }

// GET /test-types/VIBRATION → 기능 4 (+ 조건 입력폼·AI 누락 분석·매칭이 쓰는 conditionRules)
{ "code": "VIBRATION", "nameKo": "진동시험", "description": null,
  "conditionRules": [
    { "conditionKey": "sample_mass",     "label": "시료 질량",   "valueType": "NUMBER", "unit": "kg", "isRequired": true,  "specKey": "max_payload",      "comparator": "MAX_LIMIT" },
    { "conditionKey": "fixture_mass",    "label": "고정구 질량", "valueType": "NUMBER", "unit": "kg", "isRequired": true,  "specKey": "max_fixture_mass", "comparator": "MAX_LIMIT" },
    { "conditionKey": "frequency",       "label": "주파수 범위", "valueType": "RANGE",  "unit": "Hz", "isRequired": true,  "specKey": "frequency",        "comparator": "RANGE_WITHIN" },
    { "conditionKey": "excitation_axis", "label": "가진 축",     "valueType": "TEXT",   "unit": null, "isRequired": false, "specKey": null,               "comparator": "NONE" } ],
  "institutions": [
    { "id": 6, "name": "한국신뢰성기술센터", "region": "대전", "equipmentCount": 2,
      "estCostMin": null, "estCostMax": null, "estDurationDays": null, "accessScope": "UNKNOWN" },
    { "id": 2, "name": "대전테크노파크 우주·양자산업센터", "region": "대전", "equipmentCount": 1,
      "estCostMin": null, "estCostMax": null, "estDurationDays": null, "accessScope": "UNKNOWN" } ] }
```

---

### 2-1. 기관 장비 관리 — 장비 정보는 기관이 직접 등록·갱신

플랫폼은 장비 정보를 직접 입력하지 않습니다. 기관 담당자가 소속 기관 장비를 등록하고 갱신하며, 플랫폼은 입력 창구, 검증 상태 표시, 갱신 알림만 맡습니다.

| Method | URL | 권한 | 설명 |
|---|---|---|---|
| GET | `/institutions/me/equipment` | 🏛 | 우리 기관 장비 목록 (검증 상태, 마지막 갱신일, 갱신 필요 여부) |
| POST | `/equipment` | 🏛 | 장비 등록. `institutionId`는 로그인 사용자 소속으로 서버가 채움 |
| PATCH | `/equipment/{id}` | 🏛(소유기관) | 기본정보·이용조건·요금·가동률·가용상태 수정 → `infoUpdatedAt` 갱신 |
| PUT | `/equipment/{id}/specs` | 🏛(소유기관) | 실제 사양(REAL) 일괄 저장. MOCK 사양은 수정 불가 |
| PUT | `/equipment/{id}/test-types` | 🏛(소유기관) | 가능 시험유형 + 예상 비용·소요기간 (기능 4 데이터) |
| POST | `/equipment/{id}/confirm` | 🏛(소유기관) | "현재 정보가 맞음" 확인만 → `INSTITUTION_VERIFIED`, `infoUpdatedAt=now` |
| DELETE | `/equipment/{id}` | 🏛(소유기관) | 비활성화 (`is_active=false`, 기존 의뢰 이력 보존) |
| GET | `/equipment/{id}/change-logs` | 🏛(소유기관) | 누가 언제 무엇을 바꿨는지 |
| POST | `/admin/equipment/import` | 👑 | 초기 데이터(공개자료 수집 JSON) 적재 |

**초기 데이터 → 기관 인수인계 흐름**
1. 관리자가 공개자료로 모은 71대를 `import`로 적재 (`verification_status=NEEDS_MANUAL_REVIEW`, 화면에 "기관 미확인" 표시)
2. 기관 담당자가 가입·승인되면 대시보드에 "우리 기관 장비 N대 확인 필요" 표시
3. 담당자가 수정하거나 `confirm`을 누르면 `INSTITUTION_VERIFIED`로 바뀌고, 화면의 "기관 미확인" 표시가 사라짐
4. `infoUpdatedAt`이 90일을 넘으면 담당자에게 `EQUIPMENT_UPDATE_REQUIRED` 알림, 검색 결과에 "정보 갱신 필요" 표시

> MOCK 사양은 시연용이라 관리자 seed로만 들어갑니다. 기관이 REAL 사양을 입력하면 화면과 매칭은 REAL을 우선 사용합니다.

```json
// POST /equipment
{ "name": "진동시험기", "model": "V-850", "manufacturer": "...", "category": "검사/시험장비",
  "equipmentType": "TEST", "description": "...",
  "usage": { "accessScope": "EXTERNAL_ALLOWED", "feeText": "300,000원/일", "feeAmount": 300000, "feeUnit": "DAY",
             "durationText": "1~2일", "utilizationRate": 60, "availability": "AVAILABLE" },
  "specs": [ { "key": "frequency", "label": "주파수 범위", "min": 5, "max": 3000, "unit": "Hz" },
             { "key": "max_payload", "label": "최대 시료 질량", "value": 50, "unit": "kg" },
             { "key": "max_fixture_mass", "label": "고정구 질량 허용", "value": 20, "unit": "kg" } ],
  "testTypes": [ { "code": "VIBRATION", "estCostMin": 300000, "estCostMax": 600000, "estDurationDays": 2 } ] }
// → 201 { "id": 72, "externalId": "ASTRA-EQ-0072", "verificationStatus": "INSTITUTION_VERIFIED" }
```

---

## 3. AI 상담 · 조건 분석 (BE-C) — 기능 5·6

| Method | URL | 권한 | 설명 | 기능 |
|---|---|---|---|---|
| POST | `/chat/sessions` | 🏢 | 상담 시작 `{ "title"?: "..." }` | 5 |
| GET | `/chat/sessions` | 🏢 | 내 상담 목록 | 5 |
| GET | `/chat/sessions/{id}/messages` | 🏢 | 메시지 목록 | 5 |
| POST | `/chat/sessions/{id}/messages` | 🏢 | 메시지 전송 → AI 응답 (`Accept: text/event-stream` 시 SSE 스트리밍) | 5 |
| POST | `/chat/sessions/{id}/requirement` | 🏢 | 대화 내용으로 시험조건(requirement) 생성/갱신 | 6 |
| POST | `/requirements` | 🏢 | 시험조건 직접 생성 (폼 입력) | 6 |
| GET | `/requirements/{id}` | 🏢 | 시험조건 조회 (조건 항목 + 상태) | 6 |
| PATCH | `/requirements/{id}` | 🏢 | 목적·시료명 등 수정 | 6 |
| PUT | `/requirements/{id}/conditions` | 🏢 | 조건 항목 일괄 저장 (사용자 직접 수정) | 6 |
| POST | `/requirements/{id}/analyze` | 🏢 | AI 조건 분석: 누락·미확인 항목 + 질문 제안 | 6 |
| POST | `/requirements/{id}/confirm` | 🏢 | 조건 확정 (DRAFT→CONFIRMED) | 6 |

```json
// POST /chat/sessions/{id}/messages
{ "content": "위성 부품 진동시험 하고 싶어요. 시료 15kg, 20~2000Hz요" }
// → 200
{ "userMessage": { "id": 101, "role": "USER", "content": "..." },
  "assistantMessage": { "id": 102, "role": "ASSISTANT",
    "content": "고정구(지그)는 준비되어 있나요? 질량을 알려주시면 ...",
    "metadata": {
      "extractedConditions": [
        { "key": "sample_mass", "label": "시료 질량", "value": 15, "unit": "kg", "status": "CONFIRMED", "confidence": 0.95 },
        { "key": "frequency", "label": "주파수 범위", "min": 20, "max": 2000, "unit": "Hz", "status": "CONFIRMED", "confidence": 0.9 },
        { "key": "fixture_mass", "label": "고정구 질량", "unit": "kg", "status": "MISSING" } ],
      "suggestedTestType": "VIBRATION" } } }

// POST /requirements/{id}/analyze → 200
{ "requirementId": 7, "testTypeCode": "VIBRATION",
  "conditions": [ /* requirement_conditions */ ],
  "missing":     [ { "key": "fixture_mass", "label": "고정구 질량", "question": "고정구 질량은 얼마인가요?" } ],
  "unconfirmed": [ { "key": "excitation_axis", "label": "가진 축", "reason": "문장에서 명확하지 않음" } ],
  "readyForMatching": false }
```

> 설계 원칙(PPT 12p): AI 추출값은 `source=AI_EXTRACTED`, 불확실하면 `UNCONFIRMED`로 저장하고 사용자가 직접 수정 가능해야 함.
> `missing`은 그 시험유형의 `test_condition_rules` 중 `is_required=true`인데 값이 없는 조건입니다. AI는 정해진 조건 키 목록 안에서만 추출합니다.

---

## 4. AI 매칭 · 비교 (BE-C) — 기능 7

| Method | URL | 권한 | 설명 |
|---|---|---|---|
| POST | `/requirements/{id}/match-runs` | 🏢 | 매칭 실행 `{ "region"?: "대전", "limit": 5 }` |
| GET | `/match-runs/{id}` | 🏢 | 추천 결과 + 장비별 조건 비교표 |
| GET | `/requirements/{id}/match-runs` | 🏢 | 이 조건의 매칭 이력 |

**매칭 판정 규칙 (`test_condition_rules` 기준, 코드로 판정하고 LLM은 이유 문장만 생성)**

| comparator | 판정 | 예 (진동시험) |
|---|---|---|
| `RANGE_WITHIN` | 요구 값·범위가 장비 범위 안에 있으면 MET | 요구 `frequency` 20~2000 Hz ⊂ 장비 `frequency` 10~3000 Hz |
| `MAX_LIMIT` | 요구값 ≤ 장비값이면 MET | 요구 `sample_mass` 15 kg ≤ 장비 `max_payload` 40 kg |
| `MIN_LIMIT` | 장비값 ≤ 요구값이면 MET (분해능처럼 작을수록 좋은 사양) | 장비 `measurement_resolution` 0.001 mm ≤ 요구 `resolution` 0.01 mm |
| `NONE` | 비교하지 않음 → 기관 확인 | `excitation_axis` |

- 요구 조건이 비었거나 장비에 해당 사양이 없으면 `UNKNOWN`입니다.
- 단위가 다르면 규칙의 `unit`으로 환산한 뒤 비교합니다. 예: 장비 데이터의 `frequency`는 RF 장비는 GHz, 진동시험기는 Hz입니다.
- REAL 사양이 있으면 REAL을, 없으면 MOCK을 씁니다. MOCK을 썼으면 `usedMockData=true`입니다.
- 점수(예시): MET 비율 × 100, NOT_MET이 하나라도 있으면 `UNSUITABLE`, UNKNOWN이 있으면 `PARTIAL`, 전부 MET이면 `SUITABLE`.

```json
// GET /match-runs/{id}
{ "id": 3, "requirementId": 7, "algorithmVersion": "rule-v1", "createdAt": "...",
  "results": [
    { "id": 31, "rank": 1, "score": 66.7, "verdict": "PARTIAL", "usedMockData": true,
      "equipment": { "id": 48, "externalId": "ASTRA-CAND-0048", "name": "진동시험기", "institutionName": "대전테크노파크 우주·양자산업센터" },
      "reason": "주파수 범위와 시료 질량 조건을 충족합니다. 고정구 질량이 아직 정해지지 않아 기관 확인이 필요합니다.",
      "checks": [
        { "conditionKey": "frequency", "requiredText": "20~2000 Hz", "equipmentText": "10~3000 Hz", "result": "MET" },
        { "conditionKey": "sample_mass", "requiredText": "15 kg", "equipmentText": "최대 40 kg", "result": "MET" },
        { "conditionKey": "fixture_mass", "requiredText": "미정", "equipmentText": "최대 15 kg", "result": "UNKNOWN" } ] },
    { "id": 32, "rank": 2, "score": 33.3, "verdict": "UNSUITABLE", "usedMockData": true,
      "equipment": { "id": 44, "externalId": "ASTRA-CAND-0044", "name": "진동시험기1", "institutionName": "한국신뢰성기술센터" },
      "reason": "시료 질량은 충족하지만 요구 주파수 상한(2000 Hz)이 장비 범위(1800 Hz)를 넘습니다.",
      "checks": [
        { "conditionKey": "frequency", "requiredText": "20~2000 Hz", "equipmentText": "5~1800 Hz", "result": "NOT_MET" },
        { "conditionKey": "sample_mass", "requiredText": "15 kg", "equipmentText": "최대 60 kg", "result": "MET" },
        { "conditionKey": "fixture_mass", "requiredText": "미정", "equipmentText": "최대 25 kg", "result": "UNKNOWN" } ] } ],
  "disclaimer": "최종 시험 가능 여부는 기관 검토로 확정됩니다." }
```

---

## 5. 시험 의뢰 (BE-B, AI 초안은 BE-C) — 기능 8·9

| Method | URL | 권한 | 설명 | 기능 |
|---|---|---|---|---|
| POST | `/test-requests` | 🏢 | 의뢰 생성(DRAFT) `{ equipmentId, requirementId?, matchResultId? }` | 9 |
| POST | `/test-requests/ai-draft` | 🏢 | AI 의뢰서 초안 생성 → DRAFT + revision(isAiGenerated) | 8 |
| GET | `/test-requests` | 🏢 🏛 | 목록 — 기업: 내 의뢰 / 기관: 우리 기관 접수함. `?status=UNDER_REVIEW` | 9 |
| GET | `/test-requests/{id}` | 🏢 🏛 | 상세 (현재 상태, 최신 revision, 열린 보완요청, 예약) | 9 |
| PUT | `/test-requests/{id}/draft` | 🏢 | 작업본 revision 내용 저장 (DRAFT 또는 SUPPLEMENT_REQUESTED) | 8 |
| POST | `/test-requests/{id}/submit` | 🏢 | 제출 (DRAFT→SUBMITTED) | 9 |
| POST | `/test-requests/{id}/cancel` | 🏢 | 취소 `{ reason }` | 9 |
| GET | `/test-requests/{id}/revisions` | 🏢 🏛 | 제출본 이력 목록 | 10 |
| GET | `/test-requests/{id}/revisions/{no}` | 🏢 🏛 | 특정 제출본 (`?diffWith=1` 이면 변경 필드 diff 포함) | 10 |
| GET | `/test-requests/{id}/history` | 🏢 🏛 | 상태 변경 이력 | 9 |
| POST | `/test-requests/{id}/attachments` | 🏢 🏛 | 첨부 업로드 (multipart) | |
| GET | `/test-requests/{id}/attachments` | 🏢 🏛 | 첨부 목록 | |
| DELETE | `/attachments/{id}` | 업로더 | 첨부 삭제 | |

**의뢰서 본문 `content` (JSONB) 스키마 — 프론트/백 공통 계약**
(질량·주파수·크기 같은 수치 조건은 모두 `conditions[]`에 조건 키로 넣고, `sample`에는 시료 이름·수량·준비일만 둡니다.)
```json
{
  "applicant": { "companyName": "가상 A기업", "contactName": "홍길동", "phone": "010-...", "email": "..." },
  "test": { "testTypeCode": "VIBRATION", "purpose": "발사 환경 진동 내구성 확인", "standard": "MIL-STD-810H (선택)" },
  "sample": { "name": "위성 탑재 전장품", "quantity": 1, "readyDate": "2026-11-15" },
  "conditions": [ { "key": "sample_mass", "label": "시료 질량", "value": 15, "unit": "kg", "status": "CONFIRMED" },
                  { "key": "fixture_mass", "label": "고정구 질량", "value": 2, "unit": "kg", "status": "CONFIRMED" },
                  { "key": "frequency", "label": "주파수 범위", "min": 20, "max": 2000, "unit": "Hz", "status": "CONFIRMED" } ],
  "schedule": { "desiredStartDate": "2026-11-20", "desiredEndDate": "2026-11-21" },
  "equipment": { "id": 48, "externalId": "ASTRA-CAND-0048", "name": "진동시험기" },
  "notes": "자유 기재"
}
```

```json
// POST /test-requests/ai-draft
{ "requirementId": 7, "equipmentId": 48, "matchResultId": 31 }
// → 201
{ "requestId": 15, "requestNo": "REQ-20261105-0001", "status": "DRAFT",
  "revision": { "revisionNo": 1, "isAiGenerated": true, "content": { /* 위 스키마 */ } },
  "aiNotes": ["고정구 질량이 미확인 상태입니다. 제출 전 입력을 권장합니다."] }
```

---

## 6. 기관 검토 · 보완 (BE-B) — 기능 10

| Method | URL | 권한 | 설명 |
|---|---|---|---|
| POST | `/test-requests/{id}/review/start` | 🏛 | 검토 시작 (SUBMITTED/RESUBMITTED→UNDER_REVIEW), 담당자 지정 |
| POST | `/test-requests/{id}/supplements` | 🏛 | 보완 요청 (→SUPPLEMENT_REQUESTED). 답변 대기 중인 요청이 있으면 `409 SUPPLEMENT_ALREADY_OPEN` |
| GET | `/test-requests/{id}/supplements` | 🏢 🏛 | 보완 요청 목록 |
| POST | `/supplements/{id}/respond` | 🏢 | 보완 제출 → 새 revision 생성, (→RESUBMITTED) |
| POST | `/test-requests/{id}/accept` | 🏛 | 기술 검토 통과 (→SCHEDULING) |
| POST | `/test-requests/{id}/reject` | 🏛 | 반려 `{ reason }` (→REJECTED) |
| POST | `/test-requests/{id}/complete` | 🏛 | 시험 완료 처리 (CONFIRMED→COMPLETED) |

```json
// POST /test-requests/{id}/supplements
{ "version": 3,
  "message": "고정구 질량과 시료 준비 완료일을 추가로 확인 부탁드립니다.",
  "items": [ { "fieldKey": "fixture_mass", "label": "고정구 질량", "required": true },
             { "fieldKey": "sample.readyDate", "label": "시료 준비 완료일", "required": true } ],
  "dueDate": "2026-11-12" }
// fieldKey: 조건 키(fixture_mass) 또는 의뢰서 content 경로(sample.readyDate)

// POST /supplements/{id}/respond
{ "version": 4, "message": "요청하신 정보 보완했습니다.",
  "content": { /* 수정된 전체 의뢰서 content */ },
  "changeSummary": "고정구 질량 2kg, 준비 완료일 11/15 입력" }
// → 200 { "requestId": 15, "status": "RESUBMITTED", "revisionNo": 2 }
```

---

## 7. 예약 (BE-B) — 기능 11

| Method | URL | 권한 | 설명 |
|---|---|---|---|
| POST | `/test-requests/{id}/reservations` | 🏢 🏛 | 일정 제안 `{ startAt, endAt, note }` (SCHEDULING 상태에서) |
| POST | `/reservations/{id}/counter` | 🏢 🏛 | 대안 일정 제안 (→COUNTER_PROPOSED) |
| POST | `/reservations/{id}/confirm` | 🏛 | 예약 확정 → 의뢰 CONFIRMED |
| POST | `/reservations/{id}/cancel` | 🏢 🏛 | 예약 취소 |
| GET | `/equipment/{id}/reservations` | 로그인 | 장비 예약 현황(바쁜 구간만) `?from=2026-11-01&to=2026-11-30` |

**중복 예약 방지 (필수 구현)**

| 시점 | 검사 | 실패 시 |
|---|---|---|
| 일정 제안·대안 제안 | 같은 장비의 CONFIRMED 예약과 시간이 겹치는지 서비스 코드에서 먼저 조회 | `409 RESERVATION_OVERLAP` + 겹치는 구간 |
| 예약 확정 | 위 조회를 한 번 더 하고 저장. 동시에 확정해도 DB 제약(`ex_resv_no_overlap`)이 최종으로 막음 | `409 RESERVATION_OVERLAP` |
| 예약 확정 | 같은 의뢰에 이미 CONFIRMED 예약이 있는지 (`uq_resv_one_confirmed_per_request`) | `409 RESERVATION_ALREADY_CONFIRMED` |

- 시간 구간은 `[시작, 종료)`로 봅니다. 10:00~12:00 예약 뒤에 12:00~14:00 예약은 가능합니다.
- PROPOSED(제안 중) 예약끼리는 겹쳐도 됩니다. 확정된 일정만 막습니다.
- 프론트는 일정 선택 전에 `GET /equipment/{id}/reservations`로 확정 구간을 받아 달력에서 비활성 처리합니다.

```json
// 409 응답 예시
{ "success": false,
  "error": { "code": "RESERVATION_OVERLAP", "message": "선택한 시간에 이미 확정된 예약이 있습니다.",
             "details": { "conflicts": [ { "startAt": "2026-11-20T09:00:00+09:00", "endAt": "2026-11-21T18:00:00+09:00" } ] } } }
```

> 기관 예약시스템 연동 시 `externalSystem/externalRef` 사용.

---

## 8. AI 자동 추진 (BE-C) — 기능 13

| Method | URL | 권한 | 설명 |
|---|---|---|---|
| POST | `/ai/auto-runs` | 🏢 | 시작 `{ "chatSessionId": 5 }` 또는 `{ "inputText": "..." }` → 202 |
| GET | `/ai/auto-runs/{id}` | 🏢 | 진행 상태 (폴링 1~2초) |
| GET | `/ai/auto-runs/{id}/events` | 🏢 | (선택) SSE 진행 이벤트 |
| POST | `/ai/auto-runs/{id}/resume` | 🏢 | WAITING_USER 상태에서 조건 보완 후 재개 |
| POST | `/ai/auto-runs/{id}/cancel` | 🏢 | 중단 |

```json
// GET /ai/auto-runs/{id}
{ "id": 9, "status": "RUNNING", "currentStep": "MATCH",
  "steps": [ { "step": "ANALYZE", "status": "DONE",    "message": "조건 6개 추출, 누락 1개" },
             { "step": "SEARCH",  "status": "DONE",    "message": "후보 장비 8대" },
             { "step": "MATCH",   "status": "RUNNING", "message": null },
             { "step": "DRAFT",   "status": "PENDING", "message": null } ],
  "requirementId": 7, "matchRunId": null, "draftRequestId": null }
```
> 필수 조건이 `MISSING`이면 `status=WAITING_USER`로 멈추고, 프론트는 조건 입력 화면으로 유도 → 입력 후 같은 run을 `POST /ai/auto-runs/{id}/resume`. 마지막 결과는 **DRAFT 의뢰서까지만** 생성 (자동 제출 금지 — 사용자 승인 필수).

---

## 9. 가상 시뮬레이션 (BE-B) — 기능 15

| Method | URL | 권한 | 설명 |
|---|---|---|---|
| GET | `/simulation/models` | 로그인 | 모델 카탈로그 (설명·필수 입력·한계) |
| GET | `/equipment/{id}/simulation-presets` | 로그인 | 장비의 프리셋 |
| POST | `/simulation/runs` | 로그인 | 실행 `{ "presetId": "SIM-0048", "inputParameters": {...}, "requirementId"?: 7 }` |
| GET | `/simulation/runs/{id}` | 로그인 | 결과 |
| GET | `/simulation/runs` | 로그인 | 내 실행 이력 `?equipmentId=12` |

```json
// POST /simulation/runs → 201
// 요청: { "presetId": "SIM-0048", "inputParameters": { "massKg": 15, "fixtureMassKg": 2, "stiffnessNPerM": 100000,
//          "dampingRatio": 0.05, "frequencyMinHz": 20, "frequencyMaxHz": 2000 } }   ← SIM-0048 프리셋 값 그대로
{ "id": 40, "presetId": "SIM-0048", "status": "SUCCEEDED", "modelType": "SDOF_BASE_EXCITATION", "modelVersion": "0.6",
  "capability": "CALCULATED_DEMO", "isMock": true,
  "output": { "chart": { "xLabel": "Hz", "yLabel": "Transmissibility",
                         "series": [[20,0.599],[50,0.068],[100,0.020],[500,0.003],[1000,0.001],[2000,0.001]] },
              "summary": { "naturalFrequencyHz": 12.2 } },
  // 고유진동수 = √(k / (질량+고정구 질량)) / 2π = √(100000 / 17) / 2π ≈ 12.2 Hz
  "warning": "가상 시연 모델입니다. 실제 장비 성능·시험 적합성을 의미하지 않습니다." }
```
> README v0.6 정책: 화면에 "실장비 시뮬레이션 가능" 표기 금지, `capability` + DEMO 배지 동시 표시.

---

## 10. 대시보드 · 알림 (BE-A / BE-B) — 기능 14

| Method | URL | 권한 | 설명 |
|---|---|---|---|
| GET | `/dashboard/summary` | 로그인 | 역할별 요약 (아래) |
| GET | `/dashboard/equipment-stats` | 로그인 | 지역/유형별 장비 수, 기관별 보유 현황 |
| GET | `/notifications` | 로그인 | 알림 목록 `?unreadOnly=true` |
| GET | `/notifications/unread-count` | 로그인 | 헤더 배지용 |
| PATCH | `/notifications/{id}/read` | 로그인 | 읽음 |
| POST | `/notifications/read-all` | 로그인 | 모두 읽음 |

```json
// GET /dashboard/summary (COMPANY_USER)
{ "role": "COMPANY_USER",
  "requestCounts": { "DRAFT": 1, "SUBMITTED": 0, "UNDER_REVIEW": 2, "SUPPLEMENT_REQUESTED": 1, "SCHEDULING": 0, "CONFIRMED": 3 },
  "actionRequired": [ { "requestId": 15, "requestNo": "REQ-20261105-0001", "type": "SUPPLEMENT_REQUESTED", "dueDate": "2026-11-12" } ],
  "recentChats": [ { "id": 5, "title": "위성 부품 진동시험", "updatedAt": "..." } ],
  "equipmentStats": { "total": 71, "byRegion": { "대전": 32, "진주": 36 } } }
// INSTITUTION_MANAGER: requestCounts(우리 기관 접수함), pendingReview[], upcomingReservations[],
//                      equipmentNeedsUpdate (확인 필요·90일 경과 장비 수)
// ADMIN: pendingUsers, pendingInstitutions, 전체 의뢰 현황, 기관별 장비 검증률
```

---

## 11. 의뢰 상태 전이표 (BE-B 구현 · FE 버튼 노출 기준)

```
DRAFT ──submit──▶ SUBMITTED ──review/start──▶ UNDER_REVIEW ──accept──▶ SCHEDULING ──reservation confirm──▶ CONFIRMED ──▶ COMPLETED
                                                  │   ▲                                   
                                       supplements│   │review/start                        
                                                  ▼   │                                    
                                     SUPPLEMENT_REQUESTED ──respond──▶ RESUBMITTED          
UNDER_REVIEW ──reject──▶ REJECTED        (DRAFT~SCHEDULING 에서 기업 cancel ──▶ CANCELED)
```

| 현재 상태 | 액션 | 다음 상태 | 수행자 |
|---|---|---|---|
| DRAFT | submit | SUBMITTED | 기업 |
| SUBMITTED, RESUBMITTED | review/start | UNDER_REVIEW | 기관 |
| UNDER_REVIEW | supplements | SUPPLEMENT_REQUESTED | 기관 |
| SUPPLEMENT_REQUESTED | respond | RESUBMITTED | 기업 |
| UNDER_REVIEW | accept | SCHEDULING | 기관 |
| UNDER_REVIEW | reject | REJECTED | 기관 |
| SCHEDULING | reservation confirm | CONFIRMED | 기관 |
| CONFIRMED | complete | COMPLETED | 기관 |
| DRAFT ~ SCHEDULING | cancel | CANCELED | 기업 |

모든 전이는 **하나의 트랜잭션**에서: `test_requests.status/version` 갱신 + `request_status_history` INSERT + `notifications` INSERT(상대방).

---

## 12. 플랫폼 관리자 (BE-A) — 마스터

위 모든 API를 조회 범위 제한 없이 호출할 수 있고, 아래는 관리자 전용입니다.

| Method | URL | 권한 | 설명 |
|---|---|---|---|
| GET | `/admin/users` | 👑 | 회원 목록 `?role=INSTITUTION_MANAGER&status=PENDING` |
| POST | `/admin/users/{id}/approve` | 👑 | 가입 승인 (PENDING→ACTIVE) |
| POST | `/admin/users/{id}/reject` | 👑 | 가입 거절 `{ reason }` (→REJECTED) |
| PATCH | `/admin/users/{id}/status` | 👑 | 정지·해제 `{ status: "SUSPENDED" }` |
| GET | `/admin/organizations` | 👑 | 조직 목록 `?orgType=INSTITUTION&verified=false` |
| POST | `/admin/organizations` | 👑 | 기관 직접 등록 |
| POST | `/admin/organizations/{id}/verify` | 👑 | 신규 기관 신청 승인 |
| PATCH | `/admin/organizations/{id}` | 👑 | 기관 정보 수정 |
| POST | `/admin/equipment/{id}/transfer` | 👑 | 장비 소속 기관 변경 (잘못 수집된 경우) |

---

## 13. 알림 트리거
| 이벤트 | 받는 사람 | type |
|---|---|---|
| 기업 제출/재제출 | 기관 담당자 전원 | `REQUEST_SUBMITTED` / `REQUEST_RESUBMITTED` |
| 보완 요청 | 의뢰자 | `SUPPLEMENT_REQUESTED` |
| 검토 통과 / 반려 | 의뢰자 | `REQUEST_ACCEPTED` / `REQUEST_REJECTED` |
| 일정 제안 / 대안 제안 | 상대방 | `RESERVATION_PROPOSED` |
| 예약 확정 | 의뢰자 | `RESERVATION_CONFIRMED` |
| AI 자동추진 완료/대기 | 실행자 | `AI_RUN_DONE` / `AI_RUN_WAITING` |
| 기관 담당자 가입 신청 | 관리자 전원 | `USER_APPROVAL_REQUESTED` |
| 가입 승인 / 거절 | 신청자 | `USER_APPROVED` / `USER_REJECTED` |
| 장비 정보 90일 경과 | 소속 기관 담당자 | `EQUIPMENT_UPDATE_REQUIRED` |
