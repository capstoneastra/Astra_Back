# 시연 시나리오 점검표

**시나리오: "위성 전장품 15kg, 20~2000Hz 진동시험"** (team-guide §6 착수 체크리스트의 시연 시나리오)

기업이 장비 이름은 모르고 조건만 아는 상태에서 → AI 상담으로 조건 정리 → 조건에 맞는 장비를 근거와 함께 비교 →
의뢰서 초안 → 제출 → 기관 검토·보완 → 예약 확정까지 이어지는지 확인합니다.

- 각 담당자는 **자기 단계의 "확인" 칸을 채우고**, 기대 결과가 다르면 이 문서를 고쳐 주세요.
- 시연은 이 표를 처음부터 끝까지 확인한 커밋에 태그(`v0.1-midterm`, `v1.0-final`)를 붙여서 합니다 (team-guide §3).
- 장비 사양은 대부분 시연용 MOCK 입니다. 화면에 DEMO 표시가 나오는지도 함께 봅니다.

---

## 0. 준비

| 항목 | 내용 |
|---|---|
| 서버 | 시연 서버: `docker compose -f compose.prod.yaml up -d --build` (README "시연 서버 실행") / 노트북: `./gradlew bootRun` |
| DB | 처음부터 시작하려면 seed 만 있는 새 DB (로컬은 `docker compose down -v` 후 실행). 기관 8곳·대학 4곳·기업 4곳(모두 가상 이름), 장비 339대(기존 71대 + V8 시연 장비 268대, 전부 시연용 `isMock=true`), 시연 계정 7개 |
| `.env` | `JWT_SECRET`(32자 이상), `MAIL_*`(Gmail 앱 비밀번호), `CORS_ALLOWED_ORIGINS`(프론트 주소). 시연 당일만 `MAIL_LOG_CODE=true` (일회용 메일이 늦을 때 서버 로그로 코드 확인) |
| 계정 | `company@astra.demo`(기업, 가상 A기업), `manager1@astra.demo`(미리내우주환경시험센터 = 장비 48 보유), `manager2@astra.demo`(다온신뢰성시험센터 = 장비 44 보유), `univ1@astra.demo`(대학, 늘해랑대학교 항공우주공동기기원), `company2@astra.demo`(장비 보유 기업, 미르항공정밀), `admin@astra.demo`. 비밀번호는 팀 채팅 |
| 주요 장비 | **ASTRA-CAND-0048** 진동시험기 (대전TP, 주파수 10~3000Hz, 최대 시료 40kg, 고정구 15kg, 모두 MOCK) / **ASTRA-CAND-0044** 진동시험기1 (다온신뢰성시험센터, 주파수 5~1800Hz) |

---

## 1. 시연 순서

확인: ✅ 확인됨 (날짜) · ⏳ 아직 · ⚠️ 문제 있음 (비고에 적기)

| # | 단계 | 담당 | 계정 | API | 기대 결과 | 확인 |
|---|---|---|---|---|---|---|
| 1 | (선택) 새 기업 가입 | BE-A | 새 이메일 | `POST /auth/email-verifications` → `.../confirm` → `POST /auth/signup` | 메일로 6자리 코드 수신, 가입 후 `PENDING`, 로그인하면 403 `ACCOUNT_NOT_ACTIVE` | ✅ 2026-11-08 |
| 2 | (선택) 관리자 가입 승인 | BE-A | admin | `GET /admin/users?status=PENDING` → `POST /admin/users/{id}/approve` | 승인 대기 목록에 보이고, 승인하면 `ACTIVE` + 기업 조직 승인 → 로그인 가능 | ✅ 2026-11-08 |
| 3 | 기업 로그인·대시보드 | BE-A (+BE-B·C) | company | `POST /auth/login`, `GET /dashboard/summary` | 장비 요약(339대). 의뢰 상태·최근 상담 칸은 BE-B·BE-C 연결 후 채워짐 | ✅ 2026-11-09 (최근 상담 칸 BE-C 연결 확인, 의뢰 칸 ⏳) |
| 4 | 문장으로 장비 검색 | BE-A | company | `GET /search?q=위성 전장품 15kg, 20~2000Hz 진동시험` | 시험유형 `VIBRATION`, 조건 `sample_mass 15kg`·`frequency 20~2000Hz`, 장비 4대 (0044·0045·0047·0048) | ✅ 2026-11-08 |
| 5 | AI 상담 | BE-C | company | `POST /chat/sessions`, `POST /chat/sessions/{id}/messages` "위성 부품 진동시험 하고 싶어요. 시료 15kg, 20~2000Hz요" | 응답이 스트리밍(SSE)으로 나오고, 시료 질량·주파수 추출, **고정구 질량 누락**이라 되물음 | ✅ 2026-11-09 JSON·SSE 모두 (Gemini `gemini-3.5-flash`, 약 10초). 시료 15kg·주파수 20~2000Hz 추출, 고정구 무게 되물음 |
| 6 | 조건 정리·누락 보완 | BE-C | company | `POST /chat/sessions/{id}/requirement`, `POST /requirements/{id}/analyze`, `PUT /requirements/{id}/conditions` | `missing: fixture_mass` → 사용자가 2kg 입력 → `readyForMatching: true` | ⚠️ 2026-11-09 대화 → 시험조건(`POST /chat/sessions/{id}/requirement`)이 계속 502 (BE-C 확인 필요). 조건 직접 입력(`POST /requirements`) → 분석(누락 fixture_mass + 질문) → 보완 → `readyForMatching: true` 는 ✅ |
| 7 | 장비 매칭 | BE-C | company | `POST /requirements/{id}/match-runs`, `GET /match-runs/{id}` | 0045(5~2200Hz)·0047(5~2000Hz)·0048(10~3000Hz) 모두 SUITABLE 100점, 0044 는 주파수 상한 1800Hz < 2000Hz 로 NOT_MET → UNSUITABLE. 조건별 근거 표·추천 이유 문장, `usedMockData=true` (DEMO) | ✅ 2026-11-09 (조건 직접 입력 경로로 확인) |
| 8 | 장비 상세·비교 | BE-A | company | `GET /equipment/48`, `POST /equipment/compare {48,44}`, `GET /equipment/48/similar` | 사양이 MOCK·DEMO 로 표시, 비교표에서 주파수 10~3000 vs 5~1800 확인, 유사 장비에 같은 진동시험 장비 | ✅ 2026-11-08 |
| 9 | 시험 정보 안내 | BE-A | company | `GET /test-types/VIBRATION` | V6 기준 조건 5개: 필수 3개(시료 질량·고정구 질량·주파수), 선택 2개(가진 축·공인성적서 필요 여부). 가능 기관 3곳 | V6 기대값 갱신(2026-11-14), 자동 검증은 아래 CI 결과 참조 |
| 10 | AI 의뢰서 초안 | BE-C (+BE-B) | company | `POST /test-requests/ai-draft {requirementId, equipmentId: 48, matchResultId}` | `DRAFT` 의뢰 + 1번 제출본(`isAiGenerated=true`), 자동 제출 안 함 | ⏳ `/test-requests/ai-draft` API 없음 (의뢰 저장은 BE-B `TestRequestService` 구현 필요) |
| 11 | 의뢰서 수정·제출 | BE-B | company | `PUT /test-requests/{id}/draft`, `POST /test-requests/{id}/submit` | `DRAFT → SUBMITTED`, 기관 담당자에게 알림 | ⏳ |
| 12 | 기관 담당자 대시보드·장비 확인 | BE-A (+BE-B) | manager1 | `GET /dashboard/summary`, `GET /institutions/me/equipment`, `POST /equipment/48/confirm` | 소속 기관·갱신 필요 장비 수 표시, 장비 48 확인 → `INSTITUTION_VERIFIED`. 검토 대기 칸은 BE-B 연결 후 | ✅ 2026-11-08 (검토 대기 칸 ⏳) |
| 13 | 검토 시작·보완 요청 | BE-B | manager1 | `POST /test-requests/{id}/review/start`, `POST /test-requests/{id}/supplements` (예: 시료 준비 완료일) | `UNDER_REVIEW → SUPPLEMENT_REQUESTED`, 기업에게 알림 | ⏳ |
| 14 | 보완 제출 | BE-B | company | `POST /supplements/{id}/respond` | 새 제출본(revision 2) 생성, `RESUBMITTED`. 제출본 이력이 분리돼 보임 | ⏳ |
| 15 | 검토 통과·예약 확정 | BE-B | manager1 | `POST /test-requests/{id}/accept`, `POST /test-requests/{id}/reservations`, `POST /reservations/{id}/confirm` | `SCHEDULING → CONFIRMED`. 같은 시간 중복 예약은 409 `RESERVATION_OVERLAP` | ⏳ |
| 16 | (선택) 가상 시뮬레이션 | BE-B | company | `POST /simulation/runs {presetId: SIM-0048}` | 결과 그래프 + "가상 시연 모델" 경고 (DEMO) | ⏳ |
| 17 | (선택) AI 자동 추진 | BE-C | company | `POST /ai/auto-runs` | 조건 분석 → 검색 → 매칭 → 초안까지 단계별 진행, 누락 시 `WAITING_USER` | ⚠️ 2026-11-09 시작(202)은 되지만 ANALYZE 단계가 3초 만에 FAILED (6번과 같은 원인으로 보임, BE-C 확인 필요) |
| 18 | 관리자 대시보드 | BE-A (+BE-B) | admin | `GET /dashboard/summary`, `GET /dashboard/equipment-stats` | 승인 대기 회원·기관 수, 기관별 장비 확인 비율. 전체 의뢰 칸은 BE-B 연결 후 | ✅ 2026-11-08 (의뢰 칸 ⏳) |

---

## 2. 자동 점검

| 범위 | 방법 | 결과 |
|---|---|---|
| BE-A 단계 (1·2·3·4·8·9·12·18) | `DemoScenarioTest` (`./gradlew test`, CI 에서도 실행) | V6 조건 키 5개와 필수 조건 3개 검증. 최신 결과는 해당 커밋의 CI 확인 |
| BE-A 전체 API 45개 | 서버를 띄워 실제 HTTP 로 확인 (Gmail 실제 메일 수신 포함) | ✅ 2026-11-08 45/45 |
| 운영용 실행 | `compose.prod.yaml` 로컬 실행: prod 프로필, Flyway V1~V5, 헬스체크 UP, 401·Swagger·CORS | ✅ 2026-11-08 |
| BE-C 단계 실제 서버 (2026-11-09) | 상담 JSON·SSE ✅, 조건 직접 입력 → 분석 → 매칭 ✅, 대시보드 최근 상담 ✅ / 대화 → 시험조건·자동 추진 분석 ⚠️ | 🚧 |
| BE-B 단계 | 의뢰·보완·예약 구현 후 | ⏳ |

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
