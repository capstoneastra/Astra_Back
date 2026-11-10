# BE-B 작업 기록 (허영재)

시험 의뢰·상태머신·기관 검토·보완·예약·알림·가상 시뮬레이션 작업을 덩어리별로 기록합니다. 최신 작업이 위에 옵니다.
API 형식은 [`api-spec.md`](api-spec.md), 담당·일정은 [`team-guide.md`](team-guide.md) §5 를 봅니다.

| 덩어리 | 내용 | 상태 |
|---|---|---|
| 1 | 의뢰 엔티티·`RequestAction` 상태 전이표·`RequestWorkflow` (§11) | ✅ 코드 / 로컬 빌드·테스트 확인 ⏳ |
| 2 | 의뢰 생성·조회·작업본 저장·제출·취소, 제출본 이력·diff·상태 이력 (§5) | ✅ 코드 / 확인 ⏳ |
| 3 | 검토 시작·보완 요청·보완 제출·통과·반려·완료 (§6) | ✅ 코드 / 확인 ⏳ |
| 4 | 예약 제안·대안·확정·취소·장비 달력, 중복 예약 이중 방어 (§7) | ✅ 코드 / 확인 ⏳ |
| 5 | 알림 생성(`NotificationService`)·목록·읽음 (§10, §13) | ✅ 코드 / 확인 ⏳ |
| 6 | 가상 시뮬레이션 — 계산형 9종 + 규칙형 4종 (§9) | ✅ 코드 / 확인 ⏳ |
| 7 | 대시보드 `RequestDashboardQuery` 구현 (BE-A 연결) | ✅ 코드 / 확인 ⏳ |
| - | 첨부파일 (P2) | ⏳ 파일 저장소 미정 (TODO: 로컬 / S3 호환) |

> 이 코드는 Gradle 의존성을 받을 수 없는 환경에서 작성해 **문법 검사만 통과**했습니다. 병합 전에 반드시
> `./gradlew test` (Docker 필요) 로 컴파일·`ddl-auto=validate`·테스트를 확인하세요.

---

## 2026-11-09 · 1~7번 덩어리: 의뢰 워크플로우 전체

### 구조

```
request/      TestRequest·TestRequestRevision·RequestStatusHistory, RequestAction(전이표)
              RequestWorkflow   ← 모든 상태 변경이 거치는 곳 (review·reservation 도 이걸로 의뢰를 다룸)
              TestRequestServiceImpl (BE-C 용 createDraft·getDraftReceipt), RequestCommandService, RequestQueryService
              RequestDashboardQueryImpl (BE-A 대시보드), RequestContent (의뢰서 JSON), RequestViews (응답 변환)
review/       SupplementRequest, ReviewService
reservation/  Reservation, ReservationService
notification/ Notification, NotificationServiceImpl
simulation/   SimulationModel·Preset·Run, SimulationEngine(계산), SimulationService
```

- **상태 변경 한 곳**: `RequestWorkflow.begin()` (행 잠금 → 권한 → 전이 가능 → version) → 각 기능 처리 →
  `transition()` (상태 갱신 → `request_status_history` → 알림 → version 반영) 을 한 트랜잭션에서 처리
- **전이표**: `RequestAction` enum 이 api-spec §11 표 그대로. 상세 응답의 `availableActions` 도 이 표로 계산 (FE 버튼 노출 기준)
- **반려·취소 시 정리**: `RequestClosedEvent` 를 발행하면 review 가 열린 보완 요청을 CLOSED, reservation 이 확정 전 제안을 CANCELED (서로 repository 를 직접 쓰지 않음)
- **제출본 이력 분리**: DRAFT 동안은 1번 작업본을 고치고, 제출하면 고정. 보완 요청 중 `PUT /draft` 는 current+1 작업본에 저장, 보완 제출 때 그 번호로 고정 + `current_revision_no` 증가
- **의뢰 번호**: `REQ-yyyyMMdd-NNNN` (한국 날짜). `pg_advisory_xact_lock` 으로 동시 생성 시 번호 겹침 방지
- **중복 예약**: 1차 `findOverlaps` 조회 → 409 `RESERVATION_OVERLAP` + `details.conflicts`, 2차 `saveAndFlush` 의 23P01/23505 → 409. PROPOSED 끼리는 겹쳐도 됨
- **보완 요청 중복**: 서비스 검사 + `uq_supp_one_open_per_request` → 409 `SUPPLEMENT_ALREADY_OPEN`
- **보완 제출 검사**: 보완 항목 중 `required=true` 인데 비어 있으면 400 `VALIDATION_FAILED` (`details.missingItems`). fieldKey 가 조건 키면 `conditions[]` 에서, 경로면(`sample.readyDate`) 본문에서 찾음
- **기관 접수함**: 한 번도 제출 안 한 DRAFT 는 기관에 안 보임 (목록에서 빠지고 상세는 403)
- **시뮬레이션**: 모든 결과에 `warning` = "가상 시연 모델입니다. 실제 장비 성능·시험 적합성을 의미하지 않습니다." + `isMock=true` + 모델 `limitation`.
  SIM-0048 (15kg + 2kg, k=100000, ζ=0.05) → 고유진동수 12.2Hz, 20Hz 전달률 ≈ 0.598 (api-spec §9 예시와 같음).
  규칙형은 프리셋 시연 사양(MOCK)과 비교해 MET/NOT_MET/UNKNOWN. WORKFLOW·VISUALIZATION 모델은 `NOT_EXECUTED`

### API (33개 중 30개)

| 섹션 | 구현 |
|---|---|
| §5 의뢰 | `POST /test-requests`, `GET /test-requests`, `GET /{id}`, `PUT /{id}/draft`, `POST /{id}/submit`, `POST /{id}/cancel`, `GET /{id}/revisions`, `GET /{id}/revisions/{no}?diffWith=`, `GET /{id}/history` |
| §6 검토 | `POST /{id}/review/start`, `POST·GET /{id}/supplements`, `POST /supplements/{id}/respond`, `POST /{id}/accept`, `POST /{id}/reject`, `POST /{id}/complete` |
| §7 예약 | `POST /test-requests/{id}/reservations`, **`GET /test-requests/{id}/reservations` (추가)**, `POST /reservations/{id}/counter·confirm·cancel`, `GET /equipment/{id}/reservations` |
| §9 시뮬 | `GET /simulation/models`, `GET /equipment/{id}/simulation-presets`, `POST·GET /simulation/runs`, `GET /simulation/runs/{id}` |
| §10 알림 | `GET /notifications?unreadOnly=`, `GET /notifications/unread-count`, `PATCH /notifications/{id}/read`, `POST /notifications/read-all` |
| 미구현 | 첨부 3개 (P2) |

### 다른 담당자에게 (#api 방 공지용)

**BE-C (주현욱)**
- `TestRequestService.createDraft()` 구현 완료. `@Transactional` 기본(REQUIRED)이라 호출한 쪽 트랜잭션에 참여 → 호출 쪽이 롤백하면 의뢰도 롤백 (작업 로그 "같은 트랜잭션 참여 확인" 답변)
- 요청하신 **결과 조회 계약 추가**: `TestRequestService.getDraftReceipt(requestId)` → `DraftReceipt(requestId, requestNo, status, version, revisionNo, aiGenerated, content)`. 기존 `createDraft` 시그니처는 그대로
- `AiDraftService.createForApi` 는 아직 `RECEIPT_PENDING` 을 던지므로, `create()` 뒤에 `getDraftReceipt(id)` 로 응답을 채우면 시연 10단계가 이어집니다. 예:
  ```java
  var prepared = prepare(actor, input);
  Long id = create(actor, input, prepared);
  DraftReceipt r = requests.getObject().getDraftReceipt(id);
  return new AiDraftResponse(r.requestId(), r.requestNo(), r.status(),
          new AiDraftResponse.Revision(r.revisionNo(), r.aiGenerated(), r.content()), prepared.aiNotes());
  ```
- 검증 규칙: requester 는 활성 COMPANY_USER (아니면 403), 장비는 활성이어야 함 (404). content 의 applicant/test/sample/schedule/equipment 는 객체, conditions 는 key 가 있는 배열이어야 함 (400)

**BE-A (윤준수)**
- `RequestDashboardQuery`·`NotificationService` 구현이 생겨서 대시보드 의뢰 칸과 90일 갱신 알림이 실제로 동작합니다
- 그래서 `DashboardSearchApiTest` 의 기업 요약 `dataAvailable.requests` 기대값을 `false → true` 로 한 줄 바꿨습니다 (리뷰 부탁)
- 요청: 비활성 장비도 이름을 볼 수 있는 조회(예: `EquipmentService.findRefs(Collection<Long>)` — id·externalId·name·isMock, 없으면 빠짐)가 있으면 좋겠습니다. 지금은 `getDetail` 이 404 를 던질 때 트랜잭션이 rollback-only 가 되는 것을 피하려고 `RequestViews` 에서 별도 트랜잭션(REQUIRES_NEW)으로 감싸 호출합니다

**FE (연지·홍인)**
- 상세 응답 `availableActions` (예: `["SUBMIT","CANCEL"]`) 로 버튼을 노출하면 됩니다. 값은 `RequestAction` 이름: SUBMIT, START_REVIEW, REQUEST_SUPPLEMENT, RESPOND_SUPPLEMENT, ACCEPT, REJECT, CONFIRM_RESERVATION, COMPLETE, CANCEL
- 상태 변경 응답은 공통 `{ requestId, requestNo, status, version, revisionNo }`. 다음 요청에 `version` 을 보내면 동시 수정 시 409 `VERSION_CONFLICT`
- 예약 `proposedBySide` (COMPANY/INSTITUTION) 로 상대편에게 확정·대안 버튼을 보이면 됩니다. 확정은 기관만

### 정해야 할 것

- **확정된 예약 취소**: §11 표에 CONFIRMED → SCHEDULING 되돌리기가 없어서 지금은 확정된 일정 취소를 막았습니다 (409). 필요하면 전이 추가 여부 결정
- **취소 알림**: §13 에 의뢰 취소 알림 종류가 없어 보내지 않습니다. 필요하면 `REQUEST_CANCELED` 추가 (enum·FE 공지)
- **첨부파일 저장소** (로컬 / S3 호환) — 정해지면 §5 첨부 3개 구현

### 확인 방법

```bash
./gradlew test --tests kr.astra.RequestWorkflowTest   # 시연 11~16단계 흐름, 별도 DB 컨텍스트
./gradlew test                                          # 전체 (DashboardSearchApiTest 변경 포함)
```
`RequestWorkflowTest`: 생성 → 작업본 저장(version 충돌 409) → 제출(기관 알림) → 기관 접수함·대시보드 → 검토 → 보완 요청(중복 409)
→ 보완 제출(필수 항목 누락 400, revision 2, diff) → 재검토 → 통과 → 일정 제안 → 확정(중복 확정 409) → 겹치는 일정 409 / 맞닿는 일정 허용
→ 상태 이력 8건 → 완료. 그 밖에 취소 시 제안 정리, 알림 읽음, SIM-0048 계산.
