# BE-A 작업 기록 (윤준수)

플랫폼 기반(인증·권한, 장비 데이터, 관리자, 대시보드) 작업을 덩어리별로 기록합니다. 최신 작업이 위에 옵니다.
API 형식은 [`api-spec.md`](api-spec.md), 담당·일정은 [`team-guide.md`](team-guide.md) §5 를 봅니다.

| 덩어리 | 내용 | 상태 | main 반영 |
|---|---|---|---|
| 1 | 인증·권한 (회원가입·로그인·JWT·AccessGuard·시연 계정) | ✅ | PR #5 (2026-11-07) |
| 1-2 | 가입 이메일 인증 + 기업·기관 모두 관리자 승인 | ✅ | PR #5 |
| 2 | 장비 목록·검색·상세, 시험유형, 기관, EquipmentQueryService | ✅ | PR #5 |
| 3 | 기관 장비 관리, 유사·중복·비교, 관리자 정지·조직 관리·장비 이관 | ✅ | PR #6 (2026-11-08) |
| 4 | 대시보드, 자연어 검색, 90일 경과 장비 알림 | ✅ | PR #7 (2026-11-08) |
| 5 | 연동·배포 준비: CORS(PR #7) · Dockerfile · 운영 compose · 운영 설정 · 시연 점검표 (배포는 아직) | ✅ | PR #7 · PR #8 (2026-11-08) |
| 통합 1차 | BE-C 병합 후 통합 점검, SSE 끊김 수정 | ✅ | PR #10 (2026-11-09) |
| 추가 | 비교표 이용 조건(비용·기간), 정지 계정 즉시 차단, 기존 서비스(ZEUS)와의 차이 | ✅ | PR #11 (2026-11-09) |
| 6주차 | 배포, 통합 테스트, 시연 시나리오 확인 | 🔶 진행 중 | 배포는 VM 정해지면. BE-B 구현은 PR #13 (2026-11-10) 병합됨 |
| 안정화 | 인증·토큰·메일·장비 조회의 동시성·성능 수정 9건 — 윤준수, 2026-11-16 | ✅ | PR #14 (2026-11-16) |
| 7 | 기업·기관·대학 간 장비 공유로 확장 (교수님 피드백) + 가상 조직·시연 장비 268대 — 윤준수, 2026-11-17 | ✅ | `test` 브랜치 push 완료 (PR 대기) |

**지금 남은 것** (2026-11-17 기준, 7번 작업)
- `test` → main PR. be-b(의뢰·검토·예약)·be-c(상담·매칭·AI) 코드도 고쳤으니 해당 담당자 리뷰 요청
- Swagger 로 직접 호출 확인 (이번에는 자동 테스트만 함)
- FE 공지: `orgType` 필드, `?box=SENT|RECEIVED`, `?view=REQUESTER|PROVIDER`(응답 형식은 `view`로 구분), 가입 폼 대학 선택, 역할로 숨기던 의뢰·장비 관리 메뉴 노출
- 실제 VM 배포 (VM 정해지면), `POST /admin/equipment/import`(P2, 미구현)
- BE-C 대화 → 시험조건 502·자동 추진 ANALYZE 실패 (11-09 통합 점검에서 발견, 이후 해결 여부 BE-C 확인 필요)

11-09 에 남았던 것 중 끝난 것: `feature/be-a` PR(→ PR #11), 공인시험·운용 인력·보안 정보 필드(→ V6, BE-C 주현욱 2026-11-14), BE-B 의뢰·예약·알림 구현(→ PR #13)

---

## 2026-11-17 · 7번: 기업·기관·대학 간 장비 공유로 확장 + 시연 데이터 교체

**작업자**: 윤준수 (BE-A) · **작업일**: 2026-11-17 · **브랜치**: `test` (커밋 8개, 아래 1~8)

**배경**: 교수님 피드백 — 기업 → 기관 한 방향이 아니라 기업 ↔ 기업, 기관 ↔ 기관, 대학 ↔ 기업처럼 누구나 장비를 빌려주고 빌리는 플랫폼으로 확장. 시연 데이터도 가상 조직 + 실제 판매 모델 장비로 교체

**설계**: 조직 유형과 권한을 분리. 역할(`COMPANY_USER`/`INSTITUTION_MANAGER`)과 DB 컬럼명(`institution_id`)은 그대로 두고(JWT·FE·테스트 영향 최소화), **권한은 소속 조직으로만 판단**. 한 의뢰에서 누가 의뢰자이고 누가 보유 조직인지는 그 의뢰의 `requester_id`·`institution_id`로 정해짐

**1. 대학 유형** (V7, `da79be1`)
- `OrgType.UNIVERSITY` 추가. 대학은 기관과 같은 방식으로 가입·승인 (`organization.orgType`, 생략 시 INSTITUTION)
- `GET /auth/institutions`, `/institutions`, `/institutions/{id}` 응답에 `orgType` 추가. 관리자 직접 등록도 대학 가능

**2. 조직 소속 기준 권한 + 기업 장비 등록** (`18317e6`, `global/security` 변경 → 팀 공지 필요)
- `CurrentUser.belongsTo()`에서 역할 조건 제거 → 같은 조직이면 통과
- 기업도 자기 조직 장비 등록·수정·삭제. `GET /institutions`에 기업은 활성 장비 1대 이상일 때만 노출(없으면 상세 404)
- 장비 갱신·의뢰 접수 알림 대상: 기관 담당자 역할 → 보유 조직의 활성 구성원 전원

**3. 기관·대학도 의뢰** (`57a45b7`)
- 의뢰·AI 상담·시험조건·자동 추진의 기업 전용 제한(11곳) 해제. 본인 데이터만 보는 규칙은 그대로
- 자기 조직 보유 장비 의뢰 → 400 `VALIDATION_FAILED`(`details.field=equipmentId`), 매칭 추천에서도 제외

**4. 보낸/받은 의뢰** (`0a5cd63`): `GET /test-requests?box=SENT|RECEIVED`. 안 보내면 INSTITUTION_MANAGER 는 RECEIVED, 그 외 SENT(기존 동작). 검토 담당자 자동 지정도 보유 조직 구성원 전체로

**5. 대시보드 전환** (`607980f`): `GET /dashboard/summary?view=REQUESTER|PROVIDER`, 응답에 `view` 추가. FE 는 응답 형식을 `role`이 아니라 `view`로 구분해야 함

**6. 시연 데이터 교체** (V8, `9836ee5`)
- 기존 기관 9곳은 삭제하지 않고 **가상 이름으로 변경**(id 유지 → 기존 의뢰·계정 그대로). 대학 부설 2곳은 UNIVERSITY 로
- 새 가상 조직 6곳(기관 1·대학 2·기업 3, 사천·창원·고흥), 시연 계정 `univ1@astra.demo`(대학)·`company2@astra.demo`(장비 보유 기업)
- 새 장비 268대(`ASTRA-DEMO-xxxx`): 제조사·모델명·사양은 실제 판매 모델(제조사 페이지·데이터시트로 확인, 출처 링크 포함), 보유 조직·요금·가동률·가용 상태는 가상 → 전부 `isMock=true`. 확인한 사양 REAL, 추정치 MOCK/DEMO_ONLY
- 제외·조정: 주파수 사양이 없는 충격 시험기 4종 제외, 맞는 시험유형이 없는 질량특성 측정기·초음파 탐상기 6대는 SUPPORT
- 기존 장비 71대도 `isMock=true`로 바꾸고 실제 기관 출처 링크 삭제 → `includeMock=false`는 회원이 직접 등록한 장비만
- 생성: `docs/design/tools/gen_v8.py` + `equipment_catalog.json`. **V8 은 배포 후 다시 생성해서 덮어쓰면 안 됨**(Flyway 체크섬)

**7. 조합 시나리오 테스트·시연 문서** (`d10e8b5`): 대학 ↔ 기업 의뢰를 시연 계정으로 끝까지, 자기 조직 추천 제외 단위 테스트, `demo-scenario.md` §1-1(S1~S6)

**8. 문서 정리**: README(사용자·데이터 현황·흐름·시연 계정·테스트·ERD)와 이 작업 기록에 반영

**확인**
- 테스트 228개 통과 (Testcontainers. 실행 중 Docker Desktop 이 꺼지면 통합 테스트가 전부 실패하니 주의)
- seed 개수·기관 이름에 묶인 테스트 22개는 바뀔 수 있는 값을 DB 에서 세어 비교하도록 바꿈
- Swagger·실제 서버 호출은 아직 안 함

**바뀐 파일** (main 대비 56개. 다른 담당 영역 파일도 윤준수가 수정 → 해당 담당자 리뷰·공지 필요)

*BE-A 영역 (윤준수)*
- `global/security/CurrentUser`·`AccessGuard`: 소속 조직 기준 권한 (공통 코드 → 전원 공지)
- `organization/`: `OrgType`(UNIVERSITY, 유형 묶음), `Organization`, `OrganizationRepository`, `OrganizationService`, `InstitutionService`(기업은 장비 있을 때만 노출), DTO 4개(`orgType` 추가)
- `auth/dto/SignupRequest`, `auth/service/AuthService`: 대학 가입
- `admin/dto/AdminRequests`, `admin/service/AdminOrganizationService`: 대학 직접 등록
- `equipment/service/EquipmentCommandService`, `equipment/controller/EquipmentManageController`: 기업 장비 등록
- `user/service/UserService`, `user/repository/UserRepository`: 알림 대상 = 보유 조직 활성 구성원
- `dashboard/`: `DashboardView`(새), `DashboardService`, `DashboardController`, `DashboardResponses`
- 마이그레이션 `V7__university_org_type.sql`, `V8__mock_organizations_and_equipment.sql`
- 테스트: `AccessGuardTest`, `AuthFlowTest`, `AdminOrganizationTest`, `EquipmentManageTest`, `EquipmentApiTest`, `DashboardSearchApiTest`, `DatabaseSchemaTest`, `DemoScenarioTest`

*BE-B 영역 (허영재) — 윤준수 수정*
- `request/dto/RequestBox`(새): 보낸/받은 의뢰 구분 enum
- `request/controller/TestRequestController`: 목록에 `box` 파라미터, 설명 문구
- `request/service/RequestQueryService`: 목록을 역할 대신 `box`로 분기 (기본값은 기존 동작)
- `request/service/RequestCommandService`: 의뢰 생성의 기업 전용 제한 해제
- `request/service/TestRequestServiceImpl`: 의뢰자 제한을 "관리자 불가"로 바꾸고 자기 조직 장비 의뢰 차단(400)
- `request/service/RequestWorkflow`: 주석·알림 문구("장비 보유 조직에서 보완을 요청했습니다")만. 로직 변경 없음
- `review/service/ReviewService`: 담당자 자동 지정을 기관 담당자 역할 → 보유 조직 구성원 전체로
- `review/dto/ReviewInputs`, `review/controller/ReviewController`, `reservation/controller/ReservationController`: 설명 문구만
- 테스트 `RequestWorkflowTest`: 기관 → 다른 기관 의뢰, 기관 → 기업 장비 의뢰 끝까지, 대학 ↔ 기업 시연 계정 시나리오 추가

*BE-C 영역 (주현욱) — 윤준수 수정*
- `chat/service/ChatPersistence`, `chat/service/ChatSessionQueryService`: 상담의 기업 전용 제한 해제 (본인 데이터만 보는 규칙은 그대로)
- `requirement/service/RequirementApplicationService`: 시험조건의 기업 전용 제한 5곳 해제
- `aipipeline/service/PipelineStore`: 자동 추진의 기업 전용 제한 해제
- `aipipeline/service/PipelineRecovery`: 서버 재시작 시 기업·관리자 실행만 복구하던 조건 제거
- `matching/service/MatchingService`: 자기 소속 조직 장비를 추천 후보에서 제외
- 테스트: `PipelineStateTest`, `RequirementServiceTest`(기관 계정의 본인 데이터 허용으로 기대값 변경), `ChatSessionQueryTest`, `ChatSessionQueryIntegrationTest`(대시보드 시그니처 변경 반영), `MatchingServiceTest`(자기 조직 추천 제외 테스트 추가)

*문서*: `README.md`, `docs/api-spec.md`(권한·장비·의뢰·대시보드 — BE-B·BE-C 절 포함), `docs/demo-scenario.md`(§1-1 추가, 기관 이름), `docs/design/schema.sql`, `docs/design/erd.dbml`, `docs/design/tools/gen_v8.py`·`equipment_catalog.json`(새), 이 파일

---

## 2026-11-17 기록 · 안정화: 인증·토큰·메일·장비 조회 수정 (PR #14)

**작업자**: 윤준수 (BE-A) · **작업일**: 2026-11-16 (커밋 9개, PR #14 로 main 병합) · **기록일**: 2026-11-17

**동시 요청 경합**
- 재발급 토큰 동시 사용 방지 (`6f64705`): 같은 refresh 토큰으로 동시에 재발급하면 둘 다 성공할 수 있던 문제 → 토큰 행을 잠그고(`PESSIMISTIC_WRITE`) 소비
- 인증 코드 동시 확인 시 실패 횟수 경합 방지 (`d45b605`): 최근 인증 행을 잠가 실패 횟수·인증 상태 갱신을 순서대로 처리
- 계정 상태 캐시에 옛 상태가 다시 저장되는 문제 (`fd57ee4`): 조회·저장을 `ConcurrentHashMap.compute` 한 번으로 묶음

**메일·정리 작업**
- 이메일 전송 방식 변경 (`eaf092c`): 인증 코드 저장은 짧은 트랜잭션으로 끝내고 메일은 트랜잭션 밖에서 발송 (메일 서버가 느려도 DB 연결을 붙잡지 않음)
- 만료된 인증 기록 정리 (`2d877d7`): `ExpiredAuthRecordCleanup`이 매일 만료된 이메일 인증·refresh 토큰 삭제
- 계정 상태 캐시 만료 항목 정리 (`60e15fc`): 1분마다 만료된 캐시 항목 제거 (조회되지 않는 계정이 메모리에 남지 않게)

**조회 성능**
- 장비 비교 사양 조회 (`329f79b`): 장비마다 사양을 따로 조회하던 것을 한 번에 조회 (N+1 제거)
- 유사 장비 검색 (`6f589b9`): 같은 시험유형 여부를 두 번 계산하던 서브쿼리를 `LATERAL` 조인 한 번으로
- 정비 일정 목록 (`c943af1`): 달력 전체를 만든 뒤 기간만 쓰던 불필요한 조회 제거

**바뀐 파일**: `auth/service/EmailVerificationService`, `auth/service/ExpiredAuthRecordCleanup`(새), `auth/repository/EmailVerificationRepository`, `user/service/UserAccountStatusCache`, `user/service/RefreshTokenService`, `user/repository/RefreshTokenRepository`, `equipment/service/EquipmentCompareService`, `equipment/service/EquipmentScheduleService`, `equipment/repository/EquipmentJdbcRepository`, 테스트 1개(`EmailVerificationLogCodeTest`)

---

## 2026-11-09 · 비교표 이용 조건 · 정지 계정 즉시 차단 · 기존 서비스와의 차이

**1. 비교표에 비용·기간·가용성 추가** (`POST /equipment/compare`) — 시험 실무자 피드백 "일정과 비용을 사전에 비교하기 어렵다"
- 응답에 `usage`(장비별 접근 범위·요금 원문·금액·단위·소요 기간·가동률·가용 상태·기관 확인 상태·정보 갱신일) 추가. 기존 필드는 그대로 (추가만)
- 요청에 선택 `testType` → `usage[].testTypeEstimate`(그 시험의 예상 비용·소요일, 그 장비가 못 하면 `supported=false`), 없는 코드는 400
- seed 장비는 요금·예상 비용이 대부분 비어 있어 null(화면에 "정보 없음"), 기관이 입력하면 채워짐

**3. 정지 계정 즉시 차단** (`global/security` 변경 → 팀 공지 필요)
- `AccountStatusChecker`(global) + `UserAccountStatusCache`(user, 30초 캐시): JWT 필터가 요청마다 계정 상태 확인
- 승인·거절·정지·해제 시 **DB 커밋 직후** 캐시 삭제 (커밋 전에 지우면 다른 요청이 옛 상태를 다시 캐시할 수 있어서)
- 정지·거절·승인 대기 → 보호된 API 403 `ACCOUNT_NOT_ACTIVE`, 계정 없음 → 401 `AUTH_REQUIRED`, 공개 API 는 그대로. 새 에러 코드 없음
- 이전 기록(3번 덩어리)의 "정지해도 access 토큰은 최대 1시간 유효"는 **해결됨**. api-spec §0-4·§12, Swagger 설명도 고침

**4. 기존 서비스와의 차이** (README §1, 계획서 개요)
- ZEUS(과기정통부, 국가 R&D 장비)가 **2026-07-27 부터 AI 검색 시범 운영**(연구 목적 자연어 → 가까운 이용 가능 장비 추천 → 예약, 12월 정식 예정) → "자연어로 장비 찾기"는 차별점이 아님
- 보도(데일리안·아시아경제)에 없는 ZEUS 기능은 단정하지 않음. ASTRA 차별점은 조건별 충족·미충족 근거, 사양·요금·기간 비교, 의뢰서 → 기관 검토·보완 → 예약 흐름, 시연 데이터·기관 확인 표시
- 발표 때 "자연어 검색"보다 **"조건별 근거 + 시험 의뢰 흐름"**을 강조하는 게 좋음

**확인**
- 테스트 188개 통과 (새로 4개: 비교표 이용 조건 2, 즉시 차단 2), BE-C 88개 포함
- 실제 서버: 비교표 `usage`·`testTypeEstimate`(48 가능, 37 불가), 없는 testType 400 / 정지 직후 같은 토큰 403 (0.07초), 해제 직후 200, 공개 API 200, 계정 없는 토큰 401 (확인용 계정은 원래 상태로 되돌림)

**커밋**: `e627028`(비교표) `be1fa39`(즉시 차단) `406ef9e`(문서) · push 완료, PR 대기

**바뀐 파일**: `equipment/dto/EquipmentInputs.java`, `equipment/dto/EquipmentManageResponses.java`, `equipment/service/EquipmentCompareService.java`, `equipment/controller/EquipmentCompareController.java`, `global/security/AccountStatusChecker.java`(새), `global/security/JwtAuthenticationFilter.java`, `global/config/SecurityConfig.java`, `user/service/UserAccountStatusCache.java`(새), `user/service/UserService.java`, `user/repository/UserRepository.java`, `admin/controller/AdminUserController.java`, 테스트 3개(`EquipmentApiTest`, `EquipmentManageTest`, `AdminUserApprovalTest`), `README.md`, `docs/api-spec.md`, 이 파일

---

## 2026-11-09 · BE-C 병합 후 통합 점검 + SSE 끊김 버그 수정

**상황**: PR #9 (BE-C: AI 상담·시험조건·매칭·자동 추진) main 병합 → `feature/be-a` 에 fast-forward 로 받음. 합친 코드 전체 **테스트 182개 통과** (BE-A 94 + BE-C 88)

**BE-C 가 BE-A·공통 파일에서 바꾼 것** (확인함, 문제 없음)
- `ChatSessionQuery` 주석 보충(시그니처 그대로), `DashboardSearchApiTest` 의 `chats` 기대값 true 로 (상담 연결됨)
- `application.yml`·`.env.example` 에 `AI_PROVIDER`(기본 **gemini**), `GEMINI_API_KEY`, `GEMINI_MODEL`(기본 `gemini-3.8-flash`). team-guide 는 Claude 기준이라 시연 때 어느 쪽을 쓸지 팀 합의 필요

**찾아서 고친 버그 (BE-A, `SecurityConfig`)**: 로그인이 필요한 SSE 스트림(AI 상담 스트리밍)이 마지막에 끊김
- 원인: `SseEmitter` 는 응답을 마칠 때 비동기 재요청(ASYNC dispatch)을 하는데 JWT 필터는 그때 다시 돌지 않음 → Spring Security 가 비로그인으로 보고 `Access Denied` → 응답이 이미 나가는 중이라 연결이 끊김 (로그: "response is already committed")
- 수정: `auth.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()` (원래 요청에서 이미 인증·권한 통과)
- `SseAsyncDispatchTest` 추가: LLM 없이 같은 구조의 테스트용 SSE 로 확인. **수정 전 코드로는 실패, 수정 후 통과**를 직접 확인
- BE-C 테스트는 실제 서버(비동기 재요청)를 거치지 않아 드러나지 않았던 것으로 보임

**실제 서버 통합 점검 (Gemini)**
- `gemini-3.8-flash`(기본값): Google 쪽 **503 UNAVAILABLE "high demand"** (일시적 과부하) → 모든 AI 단계 502. 키는 정상 (모델 목록 조회됨)
- 이번 실행에만 `--astra.ai.gemini-model=gemini-3.5-flash` 로 다시 확인 (`.env` 는 그대로)
  - ✅ AI 상담 JSON (약 10초): 진동시험 추천, 시료 15kg·주파수 20~2000Hz 추출, 고정구 무게 되물음
  - ✅ AI 상담 SSE: delta 이벤트 … `done` 까지 끝까지 수신 (보안 수정 후)
  - ✅ 조건 직접 입력 → 분석(누락 `fixture_mass` + 질문) → 보완 → 매칭: 0045·0047·0048 SUITABLE 100점, 0044 주파수 NOT_MET → UNSUITABLE, 추천 이유 문장
  - ✅ 대시보드 최근 상담 (BE-A ← BE-C `ChatSessionQuery`), `dataAvailable.chats=true`
  - ⚠️ 대화 → 시험조건(`POST /chat/sessions/{id}/requirement`) 계속 502 (약 2초), 자동 추진 ANALYZE 단계 실패. 같은 형식 스키마로 Gemini 를 직접 부르면 200 → BE-C 쪽 처리(요청 내용·응답 검증)에서 걸리는 것으로 보임. BE-C 클라이언트가 실패 원인을 로그에 안 남겨 더 좁히지 못함
  - ⏳ AI 의뢰서 초안 API(`/test-requests/ai-draft`) 없음 (BE-B 의뢰 저장 필요)
- `docs/demo-scenario.md` 확인 칸 갱신, 7번 기대 결과를 실제 매칭 결과로 수정

**BE-C(주현욱 님)께 전달할 것**
1. 대화 → 시험조건, 자동 추진 ANALYZE 가 `gemini-3.5-flash` 에서 2~3초 만에 502
2. Gemini 실패 시 HTTP 상태·오류 메시지를 로그에 남기기 (키 제외) — 지금은 원인을 알 수 없음
3. 503(과부하)은 잠깐 뒤 재시도하거나 다른 모델로 바꾸는 처리 고려, 기본 모델 `gemini-3.8-flash` 는 현재 과부하가 잦음

**커밋**: `1cff75f`(SSE 수정) `704f385`(문서) · PR #10 병합

**바뀐 파일**: `src/main/java/kr/astra/global/config/SecurityConfig.java`, `src/test/java/kr/astra/SseAsyncDispatchTest.java`(새), `docs/demo-scenario.md`, `README.md`, 이 파일

**로컬 DB**: 점검하면서 상담 세션·시험조건·매칭 기록이 생김 (`docker compose down -v` 로 초기화 가능)

---

## 2026-11-08 · 5번 덩어리 ②~⑤: Dockerfile · 운영용 compose · 운영 설정 · 시연 점검표

**한 것**
- `Dockerfile`: 1단계 JDK 21 로 `bootJar` (테스트는 CI 담당이라 `-x test`, Testcontainers 는 이미지 빌드 안에서 못 돌림) → 2단계 JRE 21, root 아닌 `astra` 사용자, `MaxRAMPercentage=75`, 한국 시간대. `.dockerignore` 로 `.env`·`.git`·`build` 등 제외
- `compose.prod.yaml`: PostgreSQL 16(포트 외부 비공개, `pg_isready` 헬스체크) + 앱(`prod` 프로필, `curl /actuator/health` 헬스체크, `restart: unless-stopped`)
  - `DB_PASSWORD`·`JWT_SECRET` 없으면 실행 거부 (`${…:?}`), `.env` 의 `DB_URL`(localhost)은 컨테이너에서 `db:5432` 로 덮어씀, 나머지(`MAIL_*`, `CORS_*`, `ANTHROPIC_API_KEY`)는 `env_file`
- `application-prod.yml`: graceful 종료, 헬스체크 세부 숨김, Swagger `SWAGGER_ENABLED`(기본 true), 인증 코드 로그 끔
- `docs/demo-scenario.md`: 시연 18단계 (담당·계정·API·기대 결과·확인), 준비물, 문제 대처표. BE-A 단계 ✅, BE-B·BE-C 단계 ⏳ (각자 채움)
- `DemoScenarioTest`: 시연 BE-A 단계를 한 테스트에서 순서대로 (가입 → 승인 → 검색 → 상세·비교 → 기관 장비 확인 → 대시보드)

**검토하며 고친 것**
- `.env.example` 빈 값 함정: `SWAGGER_ENABLED=` 처럼 비우면 true/false 로 못 읽어 서버가 안 뜸 → 예시에 `APP_PORT=8080`, `SWAGGER_ENABLED=true` 를 직접 적음
- `MAIL_FROM=` 빈 값 + `MAIL_HOST` 설정 시 보낸 사람이 빈 값이 되어 발송 실패 → `MailConfig` 가 `MAIL_USERNAME` 을 보낸 사람으로 사용
- `DemoScenarioTest` 장비 수를 71 고정이 아니라 DB 활성 장비 수로 비교 (같은 DB 를 쓰는 `EquipmentManageTest` 가 장비를 등록하면 순서에 따라 깨질 수 있었음)
- team-guide 실행 환경의 "VM 에서 jar 실행" → compose 방식으로 통일 (6주차 계획 "VM + docker compose" 와 맞춤), 저장소 구조에 `Dockerfile`·`compose.prod.yaml` 추가
- README "API 명세 90개" → 92개 (이메일 인증 2개 추가분, 39 + 33 + 20 = 92 확인)

**확인**
- 테스트 94개 통과 (새로 `DemoScenarioTest` 1개), 처음부터 두 번 실행
- 이미지 빌드 확인 (682MB, `astra` 사용자, curl 있음)
- `compose.prod.yaml` 로컬 실행 (별도 프로젝트 `astra-prodcheck`, 확인 후 삭제): `JWT_SECRET` 없으면 실행 거부, prod 프로필, Flyway V1~V5, 헬스체크 UP(세부 숨김), 가입용 기관 9곳, 로그인 필요 API 401, Swagger 200, CORS, DB 포트 외부 비공개

**남은 것**: 실제 VM 배포 (VM 정해지면), BE-B·BE-C 단계 점검표 채우기·통합 테스트

**커밋**: `eba9e29` `b88ae2a` `fd61d1b` `65bfee6` · PR #8 병합 · 계획서 사이트에 "시연 시나리오" 탭 추가 (22번째 버전)

**바뀐 파일**: `Dockerfile`(새), `.dockerignore`(새), `compose.prod.yaml`(새), `src/main/resources/application-prod.yml`, `src/main/java/kr/astra/global/mail/MailConfig.java`, `.env.example`, `src/test/java/kr/astra/DemoScenarioTest.java`(새), `docs/demo-scenario.md`(새), `README.md`, `docs/team-guide.md`, 이 파일

---

## 2026-11-08 · 5번 덩어리 ①: CORS 설정

**왜**: FE 는 별도 저장소(React + Vite, 기본 `http://localhost:5173`)라 브라우저에서 백엔드(`localhost:8080`)를 부르면 CORS 설정이 없을 때 모든 요청이 막힘. 지금은 FE 가 MSW 목업이라 드러나지 않았음

**한 것**
- `global/config/CorsConfig`: 허용 주소 = 환경변수 `CORS_ALLOWED_ORIGINS` (쉼표로 여러 개, `http://localhost:*` 패턴 가능, 끝의 `/` 무시)
  - 기본 `http://localhost:5173`. `.env.example` 을 그대로 복사해 **빈 값**이 들어가도 기본 주소를 씀 (`${이름:기본값}` 은 빈 문자열일 때 기본값을 안 쓰기 때문)
  - 메서드 GET·POST·PUT·PATCH·DELETE·OPTIONS, 헤더 전부 허용, `Content-Disposition` 노출, credentials 안 씀(쿠키 없음, Bearer 헤더), 사전 요청 캐시 1시간
- `SecurityConfig`: `http.cors(...)` → Spring Security 앞단에서 처리되어 **401·403 응답에도 CORS 헤더** (FE 가 401 을 읽고 로그인 화면으로 보낼 수 있음)
- `application.yml` `astra.cors.allowed-origins`, `.env.example` 에 `CORS_ALLOWED_ORIGINS` 설명

**검토한 것 (계획서 대조)**
- api-spec §0: 인증은 `Authorization: Bearer` 헤더 → 헤더 허용 필요, 쿠키 없음 → credentials 불필요
- team-guide: BE-C 상담은 SSE(`POST` + `Accept: text/event-stream`) → 같은 CORS 규칙으로 사전 요청 통과 확인
- BE-C 브랜치(`feature/be-c`)에 CORS 설정이 없음 → 중복·충돌 없음, `global/`·설정 파일도 안 건드림
- team-guide 실행 환경의 오래된 문구("Flyway가 V1·V2를 적용")를 고침

**확인**
- 테스트 93개 통과 (새로 6개: `CorsTest` 4, `CorsConfigTest` 2)
- 서버를 띄워 브라우저와 같은 요청으로 확인: 허용 주소 사전 요청 200(PATCH·Authorization 허용), 다른 주소 403(허용 헤더 없음), 토큰 없는 요청 401 + CORS 헤더, SSE 경로 사전 요청 200

**문서**: api-spec §0-2 규칙 표에 CORS 줄, team-guide 실행 환경, README 실행하기·테스트 표·현재 상태

**팀 공지**: FE — 실제 서버 연결 시 기본 허용 주소는 `http://localhost:5173`, 다른 주소면 알려 주거나 서버 `.env` 의 `CORS_ALLOWED_ORIGINS` 에 추가. 쿠키 없이 `Authorization` 헤더만 쓰면 됨

**커밋**: `a6c92da` `7cb3c2e` `82db710` · PR #7 병합

**바뀐 파일**: `src/main/java/kr/astra/global/config/CorsConfig.java`(새), `src/main/java/kr/astra/global/config/SecurityConfig.java`, `src/main/resources/application.yml`, `.env.example`, `src/test/java/kr/astra/CorsTest.java`(새), `src/test/java/kr/astra/global/config/CorsConfigTest.java`(새), `README.md`, `docs/api-spec.md`, `docs/team-guide.md`, 이 파일

---

## 2026-11-08 · 4번 덩어리: 대시보드 · 자연어 검색 · 90일 알림

**API**
- `GET /dashboard/equipment-stats`: 전체 수, 지역별(가나다순)·장비 유형별·시험유형별(15개) 장비 수, 기관별 보유·기관 확인 비율
- `GET /dashboard/summary` (역할별)
  - 기업: 의뢰 상태별 수(10개 항상), 처리할 의뢰, 최근 상담, 장비 요약
  - 기관 담당자: 소속 기관, 접수함 상태별 수, 검토 대기, 다가오는 예약, 갱신 필요 장비 수(기관 확인 전·90일 경과)
  - 관리자: 승인 대기 회원·기관 수, 전체 의뢰 상태별 수, 기관별 장비 확인 비율
  - 의뢰·예약(BE-B)·상담(BE-C) 칸은 구현 전까지 0·빈 목록 + `dataAvailable=false`
- `GET /search?q=`: 문장 → 시험유형·조건·지역 해석 + 장비 (최대 20대). `interpreter` = KEYWORD(지금) / AI(BE-C 추출기 연결 시)

**다른 담당자용 인터페이스 (계약 우선, 구현은 그쪽에서)**
- BE-B: `request/service/RequestDashboardQuery` (+ `RequestActionItem`, `UpcomingReservationItem`)
- BE-C: `chat/service/ChatSessionQuery` (+ `ChatSessionSummary`), `requirement/service/ConditionExtractor` (+ `ExtractedQuery`)
- BE-A 는 `ObjectProvider` 로 받아서 구현이 없으면 빈 값·키워드 해석으로 동작. 구현 클래스에 `@Service` 만 붙이면 자동 연결

**그 외**
- `EquipmentUpdateReminder`: 매일 오전 9시(한국 시간) 마지막 갱신이 막 90일을 넘긴 장비의 담당자에게 `EQUIPMENT_UPDATE_REQUIRED` (장비마다 한 번, BE-B 알림 구현이 있을 때만). `astra.equipment.update-reminder.enabled` 로 끔
- `global/config/SchedulingConfig` (`@EnableScheduling`) 추가
- 키워드 해석: 시험유형 이름·키워드 → 시험유형, "20~2000Hz" → 단위 같은 RANGE 조건, "15kg" → 단위 같은 NUMBER 조건, 결과는 조건 규칙 표시 순서

**확인**
- 테스트 87개 통과 (새로 9개: `DashboardSearchApiTest` 7, `EquipmentUpdateReminderTest` 2), 두 번 실행
- 서버를 띄워 HTTP 확인: 장비 통계(71대, 진주 36), 역할별 요약 3종, 시연 문장 검색이 api-spec 예시와 같음 (sample_mass 15kg, frequency 20~2000Hz, 진동시험 장비 4대)

**남은 것·주의**
- BE-B·BE-C 가 인터페이스를 구현해야 의뢰·상담 칸과 AI 해석이 채워짐 (구현 후 응답 형식은 api-spec §10 그대로)
  - (2026-11-09 현재) BE-C `ChatSessionQuery` 구현됨 → 최근 상담 연결, `dataAvailable.chats=true`
  - 아직 구현 없음: BE-B `RequestDashboardQuery`·`NotificationService`(의뢰·예약 칸, 90일 알림 발송), BE-C `ConditionExtractor`(검색은 키워드 해석으로 동작)
- 키워드 해석은 단위가 정확히 같을 때만 조건으로 잡음 (kHz→Hz 같은 환산 없음). AI 추출기로 바꾸면 해결

**커밋**: `e00b35b` `c6539b5` `dce3838` · PR #7 병합

**바뀐 파일**: `chat/`, `request/`, `requirement/` 인터페이스·DTO, `dashboard/`, `equipment/`(검색·알림·집계), `global/config/SchedulingConfig`, `user`·`organization` 서비스(집계 메서드), `application.yml`, 테스트 2개, `README.md`, `docs/api-spec.md`, `docs/team-guide.md`, 이 파일

---

## 2026-11-08 · 3번 덩어리: 기관 장비 관리 · 유사/중복/비교 · 관리자 기능

**API**
- 기관 장비 관리 (기관 담당자는 소속 기관 장비만, 아니면 403 / 관리자는 전부)
  - `GET /institutions/me/equipment`: 우리 기관 장비 + 갱신 필요(`NOT_CONFIRMED` 기관 확인 전, `OUTDATED` 90일 경과)
  - `POST /equipment`: 외부 id `ASTRA-EQ-{id 4자리}`, 바로 `INSTITUTION_VERIFIED`, 사양은 REAL. 관리자는 `institutionId` 필수
  - `PATCH /equipment/{id}`(보낸 값만), `PUT /equipment/{id}/specs`(REAL 만 교체, MOCK 유지), `PUT /equipment/{id}/test-types`,
    `DELETE /equipment/{id}`(비활성화), `POST /equipment/{id}/confirm`, `GET /equipment/{id}/change-logs`
  - 모든 변경을 `equipment_change_logs` 에 `action` + `{필드: {before, after}}` 로 기록
- 유사·중복·비교: `GET /equipment/{id}/similar`(최대 10개, 이유 포함), `GET /equipment/duplicates`(같은 지역+시험유형 2대 이상), `POST /equipment/compare`(2~5대, REAL 우선)
- 관리자: `PATCH /admin/users/{id}/status`(ACTIVE↔SUSPENDED, 정지 시 refresh 토큰 전부 폐기), `GET·POST /admin/organizations`, `POST /admin/organizations/{id}/verify`, `PATCH /admin/organizations/{id}`, `POST /admin/equipment/{id}/transfer`

**정한 것**
- 갱신 필요 = 기관 확인 전이거나 마지막 갱신 90일 경과
- 순환 의존 방지를 위해 서비스 분리: `EquipmentCommandService`(관리), `EquipmentCompareService`(유사·중복·비교)
- 회원 정지는 관리자 계정·자기 자신 불가 (409 `USER_INVALID_STATUS`)

**확인**
- 테스트 78개 통과 (새로 18개: `EquipmentManageTest`, `AdminOrganizationTest`, 기존 테스트에 유사·중복·비교·정지 추가), 두 번 실행해 순서 영향 없음
- 서버를 띄워 manager1 토큰으로 HTTP 확인: 등록 → `ASTRA-EQ-0072`, 다른 기관 수정 403, 이력 CREATE→UPDATE→SPECS_UPDATE→CONFIRM, 비활성화 후 404, 유사·중복·비교 정상

**남은 것·주의**
- ~~정지한 계정의 access 토큰은 만료(최대 1시간)까지 유효~~ → **2026-11-09 해결** (다음 요청부터 바로 403)
- `POST /admin/equipment/import`(P2) 미구현 (seed 로 적재돼 있음), 90일 경과 알림은 4번 덩어리에서 구현함
- 로컬 DB 에 확인용 비활성 장비 `ASTRA-EQ-0072` 가 남아 있음 (`docker compose down -v` 로 초기화 가능)

**커밋**: `beb1b22` `d5b3caf` `b341891` `3208007` · PR #6 병합

---

## 2026-11-07 · 저장소 정리: PR 병합 방식 변경

- PR #5 를 Squash 로 병합한 뒤 브랜치 기록이 main 과 어긋나, `feature/be-a` 를 main 으로 맞추고 `--force-with-lease` 로 정리 (내용 차이 0줄 확인)
- 앞으로 PR 병합은 **Create a merge commit** (team-guide §3, README §10 수정). 저장소 설정에서 Squash·Rebase 병합 끔
- 커밋: `d2b65f3`

---

## 2026-11-07 · 2번 덩어리: 장비 조회 · 시험유형 · 기관

**API**: `GET /equipment`(q·지역·기관·유형·시험유형·가용상태·외부이용·MOCK 필터, pg_trgm 유사도 순), `GET /equipment/{id}`(사양 REAL·MOCK 모두, 같은 키면 REAL 먼저), `GET /test-types`, `GET /test-types/{code}`(조건 규칙 + 가능 기관), `GET /institutions`, `GET /institutions/{id}`

**내부 인터페이스**: `EquipmentQueryService.findCandidates·getSpecs` 구현(BE-C), `TestTypeService.getConditionRules(code)`. `TestConditionRule` 엔티티는 BE-A 가 `testtype` 패키지에 읽기 전용으로 만듦(BE-C 와 합의)

**DB**: `V5__fill_institution_region.sql` — 원본에 지역이 없던 진주뿌리기술지원센터와 장비 31대에 '진주' 채움

**문서 수정**: api-spec 예시를 seed 실제 값으로 맞춤(장비 48 주파수 라벨, 진동시험 가능 기관에 기술지원센터 추가)

**확인**: 테스트 60개 통과, HTTP·Swagger 확인

---

## 2026-11-07 · 1-2번 덩어리: 가입 이메일 인증 + 관리자 승인

- 가입 흐름: 이메일 인증(6자리 코드, 10분·60초 재발송 제한·5회) → 가입(기업·기관 모두 `PENDING`) → 관리자 승인
- `V4__email_verifications.sql`, `POST /auth/email-verifications`, `.../confirm`, 관리자 `GET /admin/users`·approve·reject
- 에러 코드 5개 추가: `EMAIL_NOT_VERIFIED`, `VERIFICATION_CODE_INVALID`, `VERIFICATION_CODE_EXPIRED`, `USER_INVALID_STATUS`, `TOO_MANY_REQUESTS`
- 사업자등록번호는 형식(10자리)만 확인, 거절된 신청의 조직은 같은 이름으로 재가입 시 재사용
- 메일: Gmail SMTP (`.env` 의 `MAIL_*`), 없으면 서버 로그 출력. 시연 대비 `MAIL_LOG_CODE` 옵션(기본 꺼짐). 일회용 메일로 실제 수신 확인

---

## 2026-11-07 · 1번 덩어리: 인증 · 권한

- 회원가입·로그인·JWT(access 1시간, refresh 14일)·재발급·로그아웃, `GET/PATCH /users/me`
- `AccessGuard`(권한 체크 유틸), JWT 필터, 로그인 필수(`require-login=true`)
- `V3__demo_accounts.sql`: 시연 계정 5개 (admin, company, manager1, manager2, pending — `@astra.demo`)
- 에러 코드 3개 추가: `INVALID_CREDENTIALS`, `EMAIL_ALREADY_EXISTS`, `ORGANIZATION_ALREADY_EXISTS`
