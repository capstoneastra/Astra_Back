# 프론트 연동 안내 (2026-11-22)

백엔드 API 는 모두 준비되어 있습니다. 프론트가 부르는 API 62개는 주소가 전부 맞고(2026-11-22 배포본 기준 대조), 이제 **서버 주소를 연결하고 회의 결정(시험 대행)으로 바뀐 부분만 맞추면** 됩니다.
요청·응답의 정확한 모양은 [`api-spec.md`](api-spec.md) 가 기준입니다.

## 1. 연결하기

| 어디 | 무엇을 | 값 |
|---|---|---|
| 프론트 빌드 | `VITE_API_BASE_URL` | 시연 서버 주소 + `/api/v1` (예: `http://서버주소:8080/api/v1`) |
| 프론트 빌드 | `VITE_SHARED_PREVIEW` | 빼거나 `false` (켜져 있으면 "백엔드 연결 전" 화면만 나옴) |
| 백엔드 `.env` | `CORS_ALLOWED_ORIGINS` | 프론트 주소. 여러 개면 쉼표 (예: `https://astra-team-preview-hw1122.almondteal65.chatgpt.site`) |
| 백엔드 `.env` | `ASTRA_RULES_STRICT` | 아래 3곳을 고치기 전까지만 `false`. 고치면 지우기(기본 `true`) |

시연 계정 비밀번호는 모두 `12345678` 입니다: `company@astra.demo`(기업), `manager1@astra.demo`(기관, 장비 48), `manager2@astra.demo`(기관, 장비 44), `company2@astra.demo`(장비 가진 기업), `univ1@astra.demo`(대학), `admin@astra.demo`(관리자), `pending@astra.demo`(승인 대기).

새 DB 로 서버를 띄운 뒤 `python docs/tools/seed_demo_requests.py --base http://서버주소:8080` 를 한 번 실행하면, 여러 상태의 의뢰(작성 중·검토 중·추가 정보 요청·일정 확정·완료·반려 등)가 미리 생겨 화면이 비어 보이지 않습니다.

## 2. 꼭 고쳐야 하는 3곳 (안 고치면 막힘)

`ASTRA_RULES_STRICT=false` 로 두면 잠시 통과되지만, 시연 전에는 고쳐야 합니다.

| 화면 | 지금 | 할 일 |
|---|---|---|
| 기관·대학 회원가입 | 400 "국가연구자번호를 입력해 주세요" | 가입 폼에 **국가연구자번호**(숫자 8자리) 칸. 요청에 `"researcherNo": "11223344"` |
| 의뢰 제출 | 400 + `missingItems` | 의뢰서에 **이용 희망 기간**(`schedule.desiredStartDate`·`desiredEndDate`), **실사용자**(`actualUser`: name·phone·email, 신청자로 미리 채워져 옴), **회계 담당자**(`accounting`: name·phone·email) 칸 |
| 의뢰 완료 (기관) | 409 "시험 결과를 먼저 등록해 주세요" | 완료 전에 **시험 결과 등록** 화면 (아래 3번) |

```json
// PUT /test-requests/{id}/draft  의 content 에 들어갈 새 칸
"actualUser": { "name": "홍길동", "phone": "010-0000-0000", "email": "hong@example.com" },
"accounting": { "name": "김회계", "phone": "02-000-0000", "email": "account@example.com" },
"schedule":   { "desiredStartDate": "2026-12-20", "desiredEndDate": "2026-12-21" }
```

## 3. 새로 만들 화면 (회의 결정: 시험 대행)

| 누가 | 화면 | API |
|---|---|---|
| 기관 | 검토 중 **추가 정보 요청** 버튼 (책임자명·성적서 종류·시료 처리·청구 방법 4개가 자동으로 요청됨) | `POST /test-requests/{id}/supplements` `{ "kind": "ADDITIONAL_INFO" }` |
| 의뢰자 | 추가 정보 입력 폼 → 보완 제출 | `POST /supplements/{id}/respond` (content 에 `responsible.name`, `report.type`, `sample.disposal`, `billing.method`) |
| 기관 | **예상 이용료** 입력 (검토 중~일정 조율 중) | `PUT /test-requests/{id}/estimated-fee` `{ "amount": 1250000, "note": "..." }` |
| 의뢰자 | 의뢰 상세에 예상 이용료 표시 | `GET /test-requests/{id}` 의 `estimatedFee` |
| 기관 | 일정 확정 뒤 **시험 결과 등록** (요약·성적서 번호·링크) | `PUT /test-requests/{id}/result` |
| 기관 | **성적서 파일 올리기** (pdf 등, 20MB) | `POST /test-requests/{id}/attachments` (multipart: `file`, `category=RESULT_REPORT`) |
| 의뢰자 | 결과 보기 + 성적서 내려받기 | `GET /test-requests/{id}` 의 `result`, `GET /test-requests/{id}/attachments`, `GET /attachments/{id}/download` |
| 모두 | 일반 첨부(시료 사진·도면 등) | 위와 같고 `category` 생략 |

## 4. 이미 쓰고 있지만 표시를 더하면 좋은 것

- 장비 추천·매칭 결과의 `availability`(AVAILABLE = 예약 가능, BUSY = 사용 중)와 `accessScope`(EXTERNAL_ALLOWED = 공동활용, INTERNAL_ONLY = 단독활용) 배지
- AI 상담 답변 메타데이터 `suggestedEquipmentIds`: 상담 AI 가 소개한 우리 DB 장비 id 목록. 답변 아래에 장비 카드로 보여 주면 좋음
- AI 요청이 429 `TOO_MANY_REQUESTS` 를 줄 수 있음 (AI 서비스 한도 초과). 502 처럼 "잠시 후 다시 시도" 안내
- 동시에 고치면 409 `VERSION_CONFLICT` → "다른 사람이 먼저 바꿨습니다, 새로고침" 안내

## 4-1. 화면 정리 요청 반영 (2026-11-24)

기존 필드·주소는 그대로이고 추가만 했습니다. 자세한 모양은 api-spec 의 해당 절.

| 요청 | 반영 | api-spec |
|---|---|---|
| 데모 문구 | 의뢰 제목 `[시연]` 말머리 없음(데모 여부는 장비 `isMock`), 알림 제목에 `[REQ-…]` 없음(`relatedRequestId` 로 연결), "장비 보유 조직" → "시험기관". 이미 쌓인 데이터도 V17 이 고침 | §13 |
| 이용료 형식 | `feeText` 가 모두 `"12,000원/시간 (부가세 별도)"` 또는 `"협의"`. `usage.feeAmount`·`feeUnit`·`vatIncluded`(새 필드) 채움 ("협의"면 금액·부가세 null, 단위 NEGOTIABLE) | §2 |
| 관리자 대시보드 | `GET /dashboard/summary`(관리자)에 `byTestType[]` { code, nameKo, requestCount(작성 중 제외), equipmentCount } | §10 |
| 비밀번호 변경 | `PATCH /users/me/password` { currentPassword, newPassword } → 200, 틀리면 400 `INVALID_PASSWORD` | §1 |
| 데모 예약 | 서버가 뜰 때(그리고 매일 0시 10분) manager1 기관 장비 3대에 **오늘부터 14일 안** 예약 4건(확정 2·제안 2)을 맞춤. 날짜가 지나면 자동으로 옮김. 끄기: `ASTRA_DEMO_RESERVATIONS=false` | §7 아래 참고 |
| 장비 목록 | `GET /equipment` 항목에 `usage`{accessScope, feeText, feeAmount, feeUnit, vatIncluded, durationText}, `keySpecs`(주요 사양 2개), `simulation`{capability, presetIds}. 시연 장비 `utilizationRate` 모두 채움 | §2 |

## 5. 순서 추천

1. 1번 연결 + `ASTRA_RULES_STRICT=false` 로 지금 화면이 실제 데이터로 도는지 확인
2. 2번 3곳 고치기 → `ASTRA_RULES_STRICT` 지우기
3. 3번 결과·성적서·이용료·추가 정보 요청 화면
4. 기관 담당자의 "우리 장비 관리"(`GET /institutions/me/equipment`)와 "제출본 비교"(`GET /test-requests/{id}/revisions/{no}?diffWith=1`) — 시연에서 차별점으로 보여 주기 좋음
5. 안 읽은 알림 배지(`GET /notifications/unread-count`) 등 나머지
