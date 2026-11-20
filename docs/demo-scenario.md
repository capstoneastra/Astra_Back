# 시연 시나리오 점검표

**시나리오: "위성 전장품 15kg, 20~2000Hz 진동시험"** (team-guide §6 착수 체크리스트의 시연 시나리오)

기업이 장비 이름은 모르고 조건만 아는 상태에서 시작해 AI 상담으로 조건 정리 → 조건에 맞는 장비를 근거와 함께 비교 →
의뢰서 초안 → 제출 → 기관 검토·보완 → 예약 확정까지 이어지는지 확인합니다.

- 각 담당자는 자기 단계의 "확인" 칸을 채우고, 기대 결과가 다르면 이 문서를 고쳐 주세요.
- 시연은 이 표를 처음부터 끝까지 확인한 커밋에 태그(`v0.1-midterm`, `v1.0-final`)를 붙여서 합니다 (team-guide §3).
- 장비 사양은 대부분 시연용 MOCK 입니다. 화면에 DEMO 표시가 나오는지도 함께 봅니다.

---

## 0. 준비

| 항목 | 내용 |
|---|---|
| 서버 | 시연 서버: `docker compose -f compose.prod.yaml up -d --build` (README "시연 서버 실행") / 노트북: `./gradlew bootRun` |
| DB | 처음부터 시작하려면 seed 만 있는 새 DB (로컬은 `docker compose down -v` 후 실행). 기관 8곳·대학 4곳·기업 4곳(모두 가상 이름), 장비 339대(기존 71대 + V8 시연 장비 268대, 전부 시연용 `isMock=true`), 시연 계정 7개 |
| `.env` | `JWT_SECRET`(32자 이상), `MAIL_*`(Gmail 앱 비밀번호), `CORS_ALLOWED_ORIGINS`(프론트 주소). 시연 당일만 `MAIL_LOG_CODE=true` (일회용 메일이 늦을 때 서버 로그로 코드 확인) |
| 계정 | `company@astra.demo`(기업, 가상 A기업), `manager1@astra.demo`(미리내우주환경시험센터 = 장비 48 보유), `manager2@astra.demo`(다온신뢰성시험센터 = 장비 44 보유), `univ1@astra.demo`(대학, 늘해랑대학교 항공우주공동기기원), `company2@astra.demo`(장비 보유 기업, 미르항공정밀), `admin@astra.demo`. 비밀번호는 모두 `12345678` (V9) |
| 주요 장비 | **ASTRA-CAND-0048** 진동시험기 (미리내우주환경시험센터, 주파수 10~3000Hz, 최대 시료 40kg, 고정구 15kg, 모두 MOCK) / **ASTRA-CAND-0044** 진동시험기1 (다온신뢰성시험센터, 주파수 5~1800Hz) |

---

## 1. 시연 순서

확인: ✅ 확인됨 (날짜) · ⏳ 아직 · ⚠️ 문제 있음 (비고에 적기)

| # | 단계 | 담당 | 계정 | API | 기대 결과 | 확인 |
|---|---|---|---|---|---|---|
| 1 | (선택) 새 기업 가입 | BE-A | 새 이메일 | `POST /auth/email-verifications` → `.../confirm` → `POST /auth/signup` | 메일로 6자리 코드 수신, 가입 후 `PENDING`, 로그인하면 403 `ACCOUNT_NOT_ACTIVE` | ✅ 2026-11-18 (메일 대신 서버 로그 코드) |
| 2 | (선택) 관리자 가입 승인 | BE-A | admin | `GET /admin/users?status=PENDING` → `POST /admin/users/{id}/approve` | 승인 대기 목록에 보이고, 승인하면 `ACTIVE` + 기업 조직 승인 → 로그인 가능 | ✅ 2026-11-18 |
| 3 | 기업 로그인·대시보드 | BE-A (+BE-B·C) | company | `POST /auth/login`, `GET /dashboard/summary` | 장비 요약(339대), 의뢰 상태별 개수, 최근 상담 | ✅ 2026-11-18 |
| 4 | 문장으로 장비 검색 | BE-A | company | `GET /search?q=위성 전장품 15kg, 20~2000Hz 진동시험` | 시험유형 `VIBRATION`, 조건 `sample_mass 15kg`·`frequency 20~2000Hz`, 진동시험 장비 20대(검색 상한, 전체 33대). 맨 앞 4대가 0044·0045·0047·0048 | ✅ 2026-11-18 |
| 5 | AI 상담 | BE-C | company | `POST /chat/sessions`, `POST /chat/sessions/{id}/messages` "위성 부품 진동시험 하고 싶어요. 시료 15kg, 20~2000Hz요" | 응답이 스트리밍(SSE)으로 나오고, 시료 질량·주파수 추출, 고정구 질량 누락이라 되물음 | ✅ 2026-11-18 `gemini-3.5-flash-lite` 로 JSON 응답(약 5초), 고정구 질량 되물음. ⚠️ 가끔 502(AI 응답 검증 실패), SSE 는 중간까지 오다 `event:error` 1회 |
| 6 | 조건 정리·누락 보완 | BE-C | company | `POST /chat/sessions/{id}/requirement`, `POST /requirements/{id}/analyze`, `PUT /requirements/{id}/conditions` | `missing: fixture_mass` → 사용자가 2kg 입력 → `readyForMatching: true` | ✅ 2026-11-18 대화 → 시험조건 200 (시료 15kg·주파수 확인, 고정구 2kg 은 `UNCONFIRMED`). 조건 직접 입력 → 분석(누락 fixture_mass + 질문) → 보완 → `readyForMatching: true` ✅. 조건 입력 시 각 조건에 `status` 필수 |
| 7 | 장비 매칭 | BE-C | company | `POST /requirements/{id}/match-runs`, `GET /match-runs/{id}` | 상위 5대(기본) 0045·0047·0048·DEMO-0036·DEMO-0037(다솜대학교) 모두 SUITABLE 100점 (V8 장비가 늘어 0044 는 상위 5대 밖). 조건별 근거·추천 이유 문장, `usedMockData=true` (DEMO) | ✅ 2026-11-18. AI 설명이 실패해도 규칙 판정 결과는 응답(설명 문장만 빠짐). 면책 문구는 한 번만 |
| 8 | 장비 상세·비교 | BE-A | company | `GET /equipment/48`, `POST /equipment/compare {48,44}`, `GET /equipment/48/similar` | 사양이 MOCK·DEMO 로 표시, 비교표에서 주파수 10~3000 vs 5~1800 확인, 유사 장비에 같은 진동시험 장비 | ✅ 2026-11-08 |
| 9 | 시험 정보 안내 | BE-A | company | `GET /test-types/VIBRATION` | V6 기준 조건 5개: 필수 3개(시료 질량·고정구 질량·주파수), 선택 2개(가진 축·공인성적서 필요 여부). 가능 조직 9곳(V8 이후) | ✅ 2026-11-18 |
| 10 | AI 의뢰서 초안 | BE-C (+BE-B) | company | `POST /test-requests/ai-draft {requirementId, equipmentId: 48, matchResultId}` | `DRAFT` 의뢰 + 1번 제출본(`isAiGenerated=true`), 자동 제출 안 함 | ✅ 2026-11-18 201 `DRAFT` + 1번 제출본 `isAiGenerated=true`, 이어서 제출하면 manager1 접수함에 보임. AI 응답 실패(502) 때는 의뢰가 남지 않음. 가끔 502 라 재시도 필요할 수 있음 |
| 11 | 의뢰서 수정·제출 | BE-B | company | `PUT /test-requests/{id}/draft`, `POST /test-requests/{id}/submit` | `DRAFT → SUBMITTED`, 기관 담당자에게 알림 | ✅ 2026-11-18 (manager1 알림 1건) |
| 11-1 | 제출 전 필수 항목 | BE-B | company | `POST /test-requests/{id}/submit` | 이용 희망 기간, 실사용자(신청자로 미리 채워짐)·회계 담당자 이름과 연락처가 없으면 400 `missingItems`. 채우면 `SUBMITTED` | ✅ 자동 점검 2026-11-20 |
| 12 | 기관 담당자 대시보드·장비 확인 | BE-A (+BE-B) | manager1 | `GET /dashboard/summary`, `GET /institutions/me/equipment`, `POST /equipment/48/confirm` | 소속 기관·갱신 필요 장비 수(13대) 표시, 검토 대기에 제출한 의뢰, 장비 48 확인 → `INSTITUTION_VERIFIED`. manager2 는 이 의뢰 403 | ✅ 2026-11-18 |
| 13 | 검토 시작·보완 요청 | BE-B | manager1 | `POST /test-requests/{id}/review/start`, `POST /test-requests/{id}/supplements` (예: 시료 준비 완료일) | `UNDER_REVIEW → SUPPLEMENT_REQUESTED`, 기업에게 알림 | ✅ 2026-11-18 |
| 13-1 | 추가 정보 요청·예상 이용료 | BE-B | manager1 → company | `POST /test-requests/{id}/supplements {kind: ADDITIONAL_INFO}`, `PUT /test-requests/{id}/estimated-fee` | 책임자명·성적서 종류·시료 처리·청구 방법 4항목 요청 → 기업이 채워 재제출. 기관이 예상 이용료 입력 → 기업 상세에 표시 | ✅ 자동 점검 2026-11-20 |
| 14 | 보완 제출 | BE-B | company | `POST /supplements/{id}/respond` | 새 제출본(revision 2) 생성, `RESUBMITTED`. 제출본 이력이 분리돼 보임 | ✅ 2026-11-18 |
| 15 | 검토 통과·예약 확정 | BE-B | manager1 | `POST /test-requests/{id}/accept`, `POST /test-requests/{id}/reservations`, `POST /reservations/{id}/confirm` | `SCHEDULING → CONFIRMED`. 같은 시간 중복 예약은 409 `RESERVATION_OVERLAP` | ✅ 2026-11-18 (이력 DRAFT→…→CONFIRMED 8단계) |
| 15-1 | 시험 결과·성적서 전달 | BE-B | manager1 → company | `PUT /test-requests/{id}/result`, `POST /test-requests/{id}/complete` | 결과 없이 완료하면 409. 결과(요약·성적서 번호·링크) 등록, 성적서 파일 업로드(`POST .../attachments category=RESULT_REPORT`) → 기업에 `RESULT_REGISTERED` 알림, 상세 `result`·첨부 목록에서 내려받기 → 완료 | ✅ 자동 점검 2026-11-20 |
| 16 | (선택) 가상 시뮬레이션 | BE-B | company | `POST /simulation/runs {presetId: SIM-0048}` | 결과 그래프 + "가상 시연 모델" 경고 (DEMO) | ✅ 2026-11-18 (점 51개 + 경고 문구) |
| 17 | (선택) AI 자동 추진 | BE-C | company | `POST /ai/auto-runs` | 조건 분석 → 검색 → 매칭 → 초안까지 단계별 진행, 누락 시 `WAITING_USER` | ✅ 2026-11-18 `{inputText}` 로 시작 202 → 고정구 질량 누락이라 ANALYZE 에서 `WAITING_USER` (이어서 진행은 미확인) |
| 18 | 관리자 대시보드 | BE-A (+BE-B) | admin | `GET /dashboard/summary`, `GET /dashboard/equipment-stats` | 승인 대기 회원·기관 수, 기관별 장비 확인 비율, 전체 의뢰 상태별 개수 | ✅ 2026-11-18 |

### 1-1. 조직 간 시험 대행 (기업·기관·대학 누구나 시험을 맡기고 맡음)

| # | 단계 | 계정 | API | 기대 결과 | 확인 |
|---|---|---|---|---|---|
| S1 | 대학이 기업 장비에 의뢰 | univ1 | `POST /test-requests {equipmentId: 미르항공정밀 장비}` → `.../submit` | 201 → `SUBMITTED`, company2 에게 `REQUEST_SUBMITTED` 알림 | ✅ 자동 점검 · 실제 서버 2026-11-18 |
| S2 | 기업이 받은 의뢰 검토·통과 | company2 | `GET /test-requests?box=RECEIVED`, `POST .../review/start`, `POST .../accept` | 받은 의뢰 목록에 보이고 `SCHEDULING` | ✅ 자동 점검 · 실제 서버 2026-11-18 |
| S3 | 기업이 대학 장비에 의뢰 | company2 | `POST /test-requests {equipmentId: 늘해랑대학교 장비}` → `.../submit` | univ1 의 받은 의뢰(기본 목록)에 보임 | ✅ 자동 점검 · 실제 서버 2026-11-18 |
| S4 | 보낸·받은 의뢰 전환 | univ1, company2 | `GET /test-requests?box=SENT`, `GET /dashboard/summary?view=REQUESTER\|PROVIDER` | 한 계정에서 두 목록·두 대시보드가 따로 보임 | ✅ 자동 점검 · 실제 서버 2026-11-18 |
| S5 | 자기 조직 장비 의뢰 차단 | univ1 | `POST /test-requests {equipmentId: 자기 조직 장비}` | 400 `VALIDATION_FAILED` (`details.field=equipmentId`). 매칭 추천에서도 빠짐 | ✅ 자동 점검 · 실제 서버 2026-11-18 |
| S6 | 기업이 장비 등록 | company | `POST /equipment` | 201, `GET /institutions`에 기업이 `orgType=COMPANY`로 나옴 | ✅ 자동 점검 · 실제 서버 2026-11-18 |

---

## 2. 자동 점검

| 범위 | 방법 | 결과 |
|---|---|---|
| BE-A 단계 (1·2·3·4·8·9·12·18) | `DemoScenarioTest` (`./gradlew test`, CI 에서도 실행) | V6 조건 키 5개와 필수 조건 3개 검증. 최신 결과는 해당 커밋의 CI 확인 |
| BE-A 전체 API 45개 | 서버를 띄워 실제 HTTP 로 확인 (Gmail 실제 메일 수신 포함) | ✅ 2026-11-08 45/45 |
| 운영용 실행 | `compose.prod.yaml` 로컬 실행: prod 프로필, Flyway V1~V5, 헬스체크 UP, 401·Swagger·CORS | ✅ 2026-11-08 |
| BE-C 단계 실제 서버 (2026-11-09) | 상담 JSON·SSE ✅, 조건 직접 입력 → 분석 → 매칭 ✅, 대시보드 최근 상담 ✅ / 대화 → 시험조건·자동 추진 분석 ⚠️ | 🚧 |
| 시연 전체 실제 서버 (2026-11-18) | 새 DB(V1~V9)에 서버를 띄워 `12345678` 시연 계정으로 1~18번·S1~S6 을 실제 HTTP 로 호출 | AI 무관 단계 전부 ✅ / 10번 AI 초안 ⚠️ / 5·7번 AI 응답 가끔 502. Gemini 기본 모델(`gemini-3.8-flash`)은 503 과부하, `gemini-3.5-flash` 는 무료 한도 429 라 `GEMINI_MODEL=gemini-3.5-flash-lite` 로 확인 |
| 조직 간 공유 (1-1) | `RequestWorkflowTest`(대학↔기업, 기관→기업 끝까지), `EquipmentManageTest`(기업 장비 등록), `MatchingServiceTest`(자기 조직 추천 제외) | `./gradlew test` |

---

## 3. 시연 중 문제가 생기면

| 증상 | 확인할 것 |
|---|---|
| 인증 메일이 안 옴 | `MAIL_LOG_CODE=true` 로 실행했으면 서버 로그 `[인증 코드]` 에서 확인. 아니면 미리 가입해 둔 계정으로 진행 |
| 프론트에서 모든 요청 실패, 콘솔에 "blocked by CORS policy" | 서버 `.env` 의 `CORS_ALLOWED_ORIGINS` 에 프론트 주소가 있는지 |
| 로그인 후 바로 401 | access 토큰 1시간 만료 → refresh. 서버 재시작 후라면 `JWT_SECRET` 이 바뀌지 않았는지 |
| 로그인 403 `ACCOUNT_NOT_ACTIVE` | 승인 대기·거절·정지 계정 → 관리자 화면에서 승인·해제 |
| AI 상담이 "AI 응답을 받지 못했습니다"(502) | Gemini 모델 과부하(503)일 수 있음 → `.env` 의 `GEMINI_MODEL` 을 다른 모델로(예: `gemini-3.5-flash`), 또는 잠시 후 재시도. 키가 비어 있지 않은지 |
| 서버가 안 뜸 | `docker compose -f compose.prod.yaml logs app`. `JWT_SECRET`·`DB_PASSWORD` 누락, `.env` 에 `SWAGGER_ENABLED=` 같은 빈 true/false 값 |
