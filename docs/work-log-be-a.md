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
| 안정화 | 인증·토큰·메일·장비 조회의 동시성·성능 수정 9건 (윤준수, 2026-11-16) | ✅ | PR #14 (2026-11-16) |
| 7 | 기업·기관·대학 간 장비 공유로 확장 (교수님 피드백) + 가상 조직·시연 장비 268대 (윤준수, 2026-11-17) | ✅ | PR #15 · PR #16 (2026-11-17) |
| 8 | 시연 계정 비밀번호 재설정 (V9, 모두 `12345678`), 2026-11-17 | ✅ | `957db90` |
| 통합 2차 | V8·V9 이후 시연 시나리오 전체를 실제 서버에서 재점검, 2026-11-18 | ✅ | `d3198ce` |
| 9 | 통합 2차에서 찾은 AI 초안·매칭·상담 문제 수정 (BE-C 코드, 윤준수가 수정), 2026-11-18 | ✅ | 41e12c0 |
| 10 | 전체 코드 점검 후 과부하·오류 위험 수정 (BE-A·B·C 전 영역), 2026-11-18 | ✅ | PR #17 |
| 11 | 회의 결정 반영: 시험 대행, 의뢰 항목 2단계, 예상 이용료, 결과·성적서, 연구자번호, 공동/단독활용 (BE-A·B·C), 2026-11-20 | ✅ | 커밋 전 |

**지금 남은 것** (2026-11-20 기준)
- FE 공지(11번): 기관·대학 가입에 `researcherNo` 필수, 의뢰서 `actualUser`·`accounting` 제출 필수, 추가 정보 요청(`kind`), 예상 이용료·결과 등록 화면, 매칭 결과 `accessScope` 배지, 완료 전 결과 등록
- 결과·성적서 파일 업로드: 저장소(서버 디스크 / S3 호환) 정한 뒤. 지금은 성적서 번호·링크만
- 예상 이용료 입력 시 의뢰자 알림 없음 (필요하면 알림 종류 추가)
- 7번에서 고친 be-b(의뢰·검토·예약)·be-c(상담·매칭·AI) 코드는 해당 담당자에게 확인 요청
- AI: Gemini 무료 키 할당량(429)·과부하(503) → API 결제 연결 필요. 연결 후 `ConditionMappingEvaluationTest` 정식 모델로 재측정 (아래 8번 참고)
- BE-C 전달: 조건 추출 검증이 단위 환산(GHz→Hz 등) 응답을 통째로 거부, 실패 원인 로그 없음, AI 상담 답변에 DB 장비 정보 미포함
- Swagger 로 직접 호출 확인 (이번에는 자동 테스트만 함)
- FE 공지: `orgType` 필드, `?box=SENT|RECEIVED`, `?view=REQUESTER|PROVIDER`(응답 형식은 `view`로 구분), 가입 폼 대학 선택, 역할로 숨기던 의뢰·장비 관리 메뉴 노출
- 실제 VM 배포 (VM 정해지면), `POST /admin/equipment/import`(P2, 미구현)
- ~~BE-C 대화 → 시험조건 502·자동 추진 ANALYZE 실패~~ → 2026-11-18 재점검에서 둘 다 동작 확인
- ~~BE-C 전달 (통합 2차) 3건~~ → 9번에서 수정. BE-C 담당자에게 수정 내용 확인 요청 필요
- ~~자동 추진 DRAFT 단계 FAILED~~ → 10번에서 수정
- 10번에서 BE-B·BE-C 코드도 많이 바꿈: 두 담당자에게 바뀐 파일 목록(아래 10번) 확인 요청
- `findCandidateIds`(매칭 후보 목록)는 아직 상한 없음. 장비가 크게 늘면 LIMIT 검토
- `application.yml` 의 Gemini 기본 모델 `gemini-3.8-flash` 가 503 과부하 상태. 시연 서버 `.env` 에 `GEMINI_MODEL` 을 명시해 둘 것

11-09 에 남았던 것 중 끝난 것: `feature/be-a` PR(→ PR #11), 공인시험·운용 인력·보안 정보 필드(→ V6, BE-C 주현욱 2026-11-14), BE-B 의뢰·예약·알림 구현(→ PR #13)

---

## 2026-11-20 · 11번: 팀 회의 결정 반영

**왜**: 팀 회의(2026-11-20)에서 서비스 성격을 "장비 대여"가 아니라 "기관이 대신 시험하고 결과·성적서를 보내 주는 방식"으로 정하고, 의뢰 항목·가입 항목을 추가하기로 함. BE-B·BE-C 영역도 윤준수가 구현(사용자 결정)

**한 것** (단계마다 테스트 먼저 → 수정 → 전체 테스트)
1. 국가연구자번호: 기관·대학 담당자 가입에 `researcherNo` 필수, 숫자 8자리 형식만 확인(목). `users.researcher_no`(V12). 내 정보·관리자 회원 목록에 노출. 기업은 없음
2. 매칭 결과 장비에 `accessScope`(EXTERNAL_ALLOWED = 공동활용, INTERNAL_ONLY = 단독활용, UNKNOWN). 예약 가능/사용 중은 기존 `availability`
3. 의뢰서 `actualUser`(실사용자, 신청자로 미리 채움)·`accounting`(회계 담당자). 제출할 때 이름 + 연락처 필수, 없으면 400 `missingItems`. AI 초안도 같은 칸을 채움
4. 추가 정보 요청: 보완 요청에 `kind`(SUPPLEMENT / ADDITIONAL_INFO, V13). ADDITIONAL_INFO 는 책임자명·성적서 종류·시료 처리·청구 방법 4항목과 안내 문구가 기본, 알림 문구도 "추가 정보". 검토 중에만(기존 보완 흐름 그대로)
5. 예상 이용료: `PUT /test-requests/{id}/estimated-fee`, 장비 보유 조직만, 검토 중~일정 조율 중. 상세 `estimatedFee`(V14)
6. 시험 결과·성적서: `test_results` 표(V15), `PUT /test-requests/{id}/result`(일정 확정 뒤, 요약·성적서 번호·http(s) 링크, 다시 보내면 수정), 의뢰자 알림 `RESULT_REGISTERED`, 상세 `result`. **결과가 없으면 완료 409**
7. 문서: README(시험 대행 문구, 핵심 흐름·상태 설명, V9~V15, 테이블 32개), ERD(README·`erd.dbml`, 생성 도구에 `test_results` 등록), `schema.sql`(V10~V15), api-spec(§1·§4·§5·§6·§13), 시연 점검표(11-1·13-1·15-1)

**확인**: 전체 테스트 328개 통과(오늘 시작 317개, 실제 AI 평가 1개 기본 건너뜀). 새 테스트: `AuthFlowTest` 2개, `EquipmentApiTest` 1개, `RequestHardeningTest` 8개. 기존 테스트는 제출 전 회계 담당자 입력(`TestApi.submitRequest`), 완료 전 결과 등록을 거치게 수정

**주의**
- 화면 계약이 바뀜: 기관·대학 가입, 의뢰 제출, 의뢰 완료가 새 필수 항목 없이는 실패함 → FE 공지 필요
- 테스트하려면 Docker Desktop 이 켜져 있어야 함 (오늘 꺼져 있어서 처음에 전부 실패했음)

**바뀐 파일 (커밋 전)**: `git status` 참고. 마이그레이션 V12~V15, auth·user·admin(연구자번호), equipment·matching(accessScope), request·review·notification(의뢰 항목·추가 정보·이용료·결과), llm(AI 초안 칸), 테스트, README·api-spec·demo-scenario·schema.sql·erd.dbml·ERD 도구, 이 파일

---

## 2026-11-18 · 10번: 전체 코드 점검과 과부하·오류 위험 수정

**왜**: 시연·운영 중 서버가 멈추거나 500 이 나는 곳을 미리 찾으려고 전체 코드(약 15,000줄)를 영역별로 점검. 범위는 사용자가 "전부(규모 대비 항목 포함)"로 정함. BE-B·BE-C 코드도 윤준수가 수정

**가장 컸던 것**
- DB 연결 풀 고갈: 의뢰 목록·상세가 장비마다 연결을 하나 더 열었고(`REQUIRES_NEW`), 매칭은 AI 호출(최대 45초) 동안 연결을 쥐고 있었음. 기본 풀 10개라 동시 요청 10개쯤이면 로그인까지 멈출 수 있었음 → 같은 트랜잭션에서 한 번에 조회, 매칭은 AI 호출을 트랜잭션 밖으로
- 상담 한 번에 AI 3번 호출 → 시험유형을 알면 판별 생략, 조건 추출이 실패해도 답변은 이어감
- 조건 추출이 항목 하나만 틀려도 전체 502 → 항목별로 버리거나 UNCONFIRMED 로 낮춤, ℃·도·%·배·"2,400"·GHz 환산 허용

**영역별로 한 것**
- 설정(`application.yml`): DB 연결 풀 20(`DB_POOL_SIZE`), 연결 대기 10초, 목록 size 상한 100, 메일 서버 타임아웃 5초
- 인증·회원·공통: 로그인 기록이 관리자 정지를 덮어쓰던 문제(`users.version`, V10), 72바이트 넘는 비밀번호 500 → 400, 프레임워크 4xx·DB 제약 위반이 500 으로 나가던 것, JWT 필터의 DB 오류 응답 형식, 없는 이메일 로그인도 BCrypt 비교(가입 여부 노출 방지), 인증 메일·로그인 IP 제한(`astra.rate-limit.*`, 기본 메일 IP당 분당 5·전체 60, 로그인 IP당 20), 같은 이메일 동시 요청 잠금, 중복 코드(`Hashes`, `BusinessException.invalidField`) 정리
- 장비·대시보드: 90일 갱신 알림을 기관별 요약 1건·100대씩 나눠 처리, "갱신 필요" 기준을 한 곳으로(대시보드 수 = 목록 needsUpdate 수), 검색의 트랜잭션 제거, 관리자 이관 시 행 잠금, 공백 장비명 400, 시험유형·사양 사전 메모리 캐시
- 의뢰·예약·시뮬레이션: 일정 확정·취소 동시 처리 시 취소된 일정이 확정되던 문제(`reservations.version`, V11, 잠근 뒤 조회), 지난 시각 일정 확정 거부, 보완 항목 null 로 의뢰가 멈추던 문제, 대시보드·제출본 목록 반복 조회 제거, 시뮬레이션 넘침 입력 500 → 400·입력값 크기 제한
- AI·상담·자동 추진·매칭: AI 작업 스레드를 상담(16)·자동 추진(2)으로 분리, 동시 AI 호출 8개 제한(`astra.ai.*`), Gemini 429 는 "잠시 후 다시" 429 로·503 은 1회 재시도·상태 코드 로그, 조건 분석은 AI 실패 시 기본 질문, 상담 기록에 프롬프트 전체 대신 해시만 저장, 매칭 후보별 반복 조회를 한 번에, 자동 추진 초안 단계 성공 처리(`confirmReceipt`), 서버 종료 중이던 자동 추진은 재시작 후 이어서, 실패한 질문은 다음 대화에서 제외

**방법**: 영역별 에이전트 4개가 각자 별도 작업 폴더(git worktree)에서 테스트 먼저 쓰고(실패 확인) 수정 → 이 폴더로 합침 → 작업 폴더 삭제

**확인**
- 전체 테스트 317개 통과(실제 AI 평가 1개 기본 건너뜀). 오늘 아침 229개에서 88개 늘어남
- 새 DB 실제 서버: 1~18번·S1~S6 다시 통과, 상담 JSON·SSE 첫 시도 성공, AI 초안 201, 매칭 면책 문구 1번. Google 503·429 는 로그에 상태 코드가 남고 429 로 응답하는 것 확인

**주의**
- Flyway V10(`users.version`)·V11(`reservations.version`) 추가
- 테스트용 `src/test/resources/application.properties` 에서 IP 제한을 크게 풀어 둠(테스트는 모두 같은 IP)
- 시연 때 여러 명이 한 공유기(같은 IP)로 접속해 로그인을 반복하면 IP당 분당 20회 제한에 걸릴 수 있음. 필요하면 `.env` 에 `astra.rate-limit.login-per-ip-per-minute=100` 처럼 적어 올리기 (`.env` 는 속성 파일로 읽히므로 이 이름 그대로)

**커밋** (`feature/be-a` push 완료, PR 전): `9eea6e3` 설정 · `43bb3ac` 인증·회원·공통 · `a5c29eb` 장비·대시보드 · `28dbdab` 의뢰·예약·시뮬레이션 · `5c413c7` AI·상담·매칭 · `acb47d5` 문서

**바뀐 파일: BE-A (내 담당)**
- 설정: `src/main/resources/application.yml`, 테스트 `PageSizeLimitTest`
- 인증·회원·관리자·공통: `auth/controller/AuthController`, `auth/dto/SignupRequest`, `auth/repository/EmailVerificationRepository`, `auth/service/AuthService`·`EmailVerificationService`·`AuthRateLimiter`(새), `user/entity/User`, `user/repository/UserRepository`, `user/service/UserService`·`RefreshTokenService`, `admin/service/AdminOrganizationService`, `global/config/SecurityConfig`, `global/exception/BusinessException`·`GlobalExceptionHandler`, `global/security/JwtAuthenticationFilter`·`FilterErrorWriter`(새)·`Hashes`(새)·`TokenBucketRateLimiter`(새), `global/validation/PasswordPolicy`(새)·`BcryptPassword`(새), `db/migration/V10__user_version.sql`(새)
- 장비·대시보드·시험유형: `equipment/entity/Equipment`, `equipment/dto/EquipmentInputs`, `equipment/repository/EquipmentJdbcRepository`, `equipment/service/EquipmentCommandService`·`EquipmentUpdateReminder`·`SearchService`·`SpecDefinitionService`, `testtype/service/TestTypeService`, `dashboard/service/DashboardService`, `dashboard/dto/DashboardResponses`
- 테스트: `AuthHardeningTest`, `UserConcurrencyTest`, `auth/service/AuthRateLimiterTest`, `global/exception/GlobalExceptionHandlerTest`, `global/security/HashesTest`·`JwtAuthenticationFilterTest`·`TokenBucketRateLimiterTest`, `user/service/UserServiceAuthenticateTest`, `EquipmentManageTest`, `equipment/service/EquipmentUpdateReminderTest`·`SearchServiceTransactionTest`·`SpecDefinitionServiceTest`, `testtype/service/TestTypeServiceCacheTest`, `dashboard/service/DashboardServiceTest`, `src/test/resources/application.properties`(새)

**바뀐 파일: 다른 담당 영역 (담당자 확인 필요)**
- BE-A 파일이지만 BE-C 매칭용으로 추가: `equipment/service/EquipmentQueryService`·`EquipmentQueryServiceImpl`·`EquipmentService`(일괄 조회 메서드), `EquipmentJdbcRepository.countCandidates`
- BE-B: `request/`(`RequestViews`, `RequestCommandService`, `RequestQueryService`, `TestRequestServiceImpl`, `TestRequestRevisionRepository`), `review/`(`ReviewService`, `ReviewInputs`, `SupplementRequestRepository`), `reservation/`(`Reservation`, `ReservationRepository`, `ReservationService`), `simulation/`(`SimulationEngine`, `SimulationService`), `V11__reservation_version.sql`
- BE-C: `llm/`(`AiTaskExecutor`, `LlmConcurrencyLimiter`(새), `GeminiLlmClient`, `AnthropicLlmClient`, `PromptLoader`, `AiDraftService`, `AiFallback`(새), `ConditionExtractionService`, `RequirementAnalysisService`, `StructuredJson`), `chat/`(`ChatService`, `ChatPersistence`), `aipipeline/`(`PipelineRun`, `PipelineService`, `PipelineWorker`), `matching/MatchingService`, `requirement/`(`ConditionValidator`, `UnitConverter`)

---

## 2026-11-18 · 9번: AI 의뢰서 초안·매칭·상담 응답 수정

**왜**: 통합 2차 점검에서 ① AI 의뢰서 초안 API 가 항상 500 ② AI 설명이 실패하면 매칭 전체가 502 ③ 추천 이유 면책 문구 중복, 상담 응답에 프롬프트 노출. BE-C 담당 코드지만 시연 전이라 윤준수가 수정 (사용자 결정)

**한 것**
- `AiDraftService.createForApi`: BE-B 가 추가한 `getDraftReceipt` 로 응답을 채움. AI 호출은 트랜잭션 밖, 저장과 결과 조회만 한 트랜잭션(`TransactionOperations`)이라 AI 대기 중 DB 연결을 잡지 않고, 실패하면 고아 DRAFT 가 남지 않음
- 같은 곳: 의뢰서 `content.conditions` 를 Java 객체 대신 `key` 를 가진 JSON 객체로 저장. 그대로 두면 BE-B `RequestContent.validate` 가 400 으로 거부함 (점검 중 실제 서버에서 발견)
- `MatchingService`: `AI_UPSTREAM_ERROR` 면 경고 로그만 남기고 설명 없이 규칙 판정 결과로 응답. AI 설명에 면책 문구가 들어 있으면 지우고 끝에 한 번만 붙임
- `ChatResponse.Message`: `prompt`·`promptInput`·`rawResponse` 는 DB(감사용)에만 두고 API 응답에서 뺌 (최상위와 `conversation` 안 모두)
- Swagger: AI 의뢰 초안 설명을 실제 동작으로 고침

**확인**
- 새 테스트 7개(500 을 기대하던 기존 테스트 1개 대체): `AiDraftServiceTest` 3개(영수증 응답, AI 실패 시 저장 안 함, 조건 JSON 형식), `MatchingServiceTest` 2개(AI 실패 시 규칙 결과, 면책 문구 1번), `ChatMessageResponseTest` 2개. 모두 수정 전 실패를 확인한 뒤 고침
- 전체 테스트 235개 통과(실제 AI 평가 1개는 기본 건너뜀)
- 새 DB 실제 서버(`gemini-3.5-flash-lite`): 매칭 200·면책 문구 1번, AI 초안 201 `DRAFT`·`isAiGenerated=true` → 제출하면 manager1 접수함에 보임, AI 실패(502) 요청은 의뢰를 남기지 않음, 상담 응답에 `promptInput`·`rawResponse` 없음

**바뀐 파일 (`41e12c0`)**: `src/main/java/kr/astra/llm/service/AiDraftService.java`, `src/main/java/kr/astra/llm/controller/AiDraftController.java`, `src/main/java/kr/astra/matching/service/MatchingService.java`, `src/main/java/kr/astra/chat/dto/ChatResponse.java`, `src/test/java/kr/astra/llm/AiDraftServiceTest.java`, `src/test/java/kr/astra/matching/MatchingServiceTest.java`, `src/test/java/kr/astra/chat/ChatMessageResponseTest.java`(새), `docs/demo-scenario.md`, 이 파일

---

## 2026-11-18 · 문서 문장 정리 (humanizer)

**한 것**: 저장소의 md 문서 11개 중 10개에서 AI 문체(문장 잇는 대시 "—", 꾸밈용 굵은 글씨, 화살표로 문장 잇기, 같은 말 반복)를 걷어 냄. README, api-spec, team-guide, PR 템플릿, 작업 기록 3개(BE-A·B·C), demo-scenario, data-dictionary, backend-hardening-handoff. GLOSSARY 는 고칠 곳 없음

**안 바꾼 것**: 코드 블록·인라인 코드·경로·API·숫자·날짜·표 구조·상태 표시·제목(README 첫 줄 대시만 콜론으로). 상태 변화를 나타내는 화살표(DRAFT → SUBMITTED 등)와 실제 오해를 바로잡는 "X가 아니라 Y" 문장은 유지. `src/main/resources/prompts/*.md` 는 AI 프롬프트라 제외

**확인**: 바꾸기 전후의 인라인 코드·숫자·링크·코드 블록·제목을 스크립트로 비교해 차이 없음

**남은 것**: team-guide 표에 원래부터 글자가 빠진 칸 2곳(376행 `/revisions/{no}` "특정 제출본 ( 이면…", 432행 `/ai/auto-runs` "시작 또는 → 202"). 내용을 아는 사람이 채워야 함

---

## 2026-11-18 · 통합 2차: 시연 시나리오 실제 서버 재점검

**왜**: V8 에서 조직·장비 데이터가 바뀌고 V9 로 시연 계정 비밀번호가 생겨서, "백엔드는 이 순서로 된다"를 실제 서버로 다시 확인해 프론트 문제와 구분하려고

**방법**: 기존 로컬 DB 는 두고 임시 PostgreSQL(5433)에 새 DB 를 만들어 V1~V9 적용 → `bootRun` → 시연 계정 6개 `12345678` 로그인 → `demo-scenario.md` 1~18번과 S1~S6 을 실제 HTTP 로 호출 (메일은 `MAIL_HOST` 를 비워 서버 로그 코드로 가입). 끝나고 임시 DB 삭제

**결과** (자세한 건 `demo-scenario.md` 확인 칸)
- 로그인·가입·승인·대시보드·검색·장비 상세/비교/유사·시험 정보·의뢰 작성/제출/검토/보완/재제출/예약 확정/중복 예약 409·시뮬레이션·관리자 대시보드·조직 간 공유 S1~S6: 전부 기대대로
- AI 단계: 기본 모델 `gemini-3.8-flash` 503, `gemini-3.5-flash` 429 → `GEMINI_MODEL=gemini-3.5-flash-lite` 로 재실행. 상담(고정구 질량 되물음)·대화 → 시험조건·조건 분석·매칭·자동 추진(`WAITING_USER`) 동작. 상담·매칭은 가끔 502 (AI 응답 검증 실패, 재시도하면 성공)
- 10번 AI 의뢰서 초안: API 는 있지만 `AiDraftService.createForApi` 가 항상 500 을 던짐 → BE-C 전달
- 점검표 기대값을 V8 데이터에 맞게 고침: 검색 4대 → 20대(상한), 매칭 상위 5대에 다솜대학교 장비 2대 포함·0044 는 밖, 시험 정보 가능 조직 3곳 → 9곳, 주요 장비 소속 "대전TP" → 미리내우주환경시험센터

**점검하다 알게 된 것 (코드 수정 안 함)**
- 로컬 `.env` 의 `JWT_SECRET` 이 비어 있어 임시 키로 실행됨 (재시작하면 로그인 풀림)
- `POST /requirements` 조건마다 `status` 가 필수 (없으면 400). FE 공지가 필요할 수 있음
- `POST /ai/auto-runs` 본문 필드는 `inputText` 또는 `chatSessionId`

**바뀐 파일 (`d3198ce`)**: `docs/demo-scenario.md`, 이 파일

---

## 2026-11-17 · 8번: 시연 계정 비밀번호 재설정

**왜**: V3·V8 시연 계정 해시의 원문을 아는 사람이 없어 프론트·시연에서 로그인할 수 없었음

**한 것**
- `V9__demo_account_password.sql`: 시연 계정 7개(admin, company, company2, manager1, manager2, univ1, pending `@astra.demo`)의 비밀번호를 `12345678` 해시로 교체. V3·V8 은 그대로
- 시연 전용 계정이라 공개해도 되는 값으로 정함(윤준수). README 시연 계정, api-spec §1, demo-scenario 준비물에 비밀번호 표기
- `AuthFlowTest`: 시연 계정 6개 `12345678` 로그인 200, `pending` 은 403 `ACCOUNT_NOT_ACTIVE`

**확인**: 테스트 229개 통과(1개는 실제 AI 평가라 기본 건너뜀)

**같은 날 확인한 것 (코드 변경 없음)**
- 실제 AI 조건 매핑 평가(`ASTRA_RUN_LLM_EVAL=true`): `gemini-3.5-flash` 는 429·503 으로 25개 모두 호출 실패. 한도가 남은 `gemini-3.5-flash-lite` 로 19/25 완전 일치. 응답이 온 19개는 전부 정확, 나머지 6개(GHz 3건, -70°C, 중량 표현, 배터리 전압·전류)는 호출 오류. 조건 추출 검증이 환산된 값을 원문에서 못 찾아 거부했을 가능성이 큼 (로그가 없어 확정 못 함)
- Gemini 구독(앱)과 API 사용량은 결제가 별도라서 API 키 프로젝트에 Google Cloud 결제 연결 필요

**주의**: 운영 서버에 그대로 배포하면 관리자 계정도 `12345678` 로 로그인됨. 외부 공개 전에 관리자 비밀번호는 바꾸는 것을 권장

**바뀐 파일 (`957db90`, 문서는 `d3198ce`)**: `src/main/resources/db/migration/V9__demo_account_password.sql`(새), `src/test/java/kr/astra/AuthFlowTest.java`, `README.md`, `docs/api-spec.md`, `docs/demo-scenario.md`, 이 파일

---

## 2026-11-17 · 7번: 기업·기관·대학 간 장비 공유로 확장 + 시연 데이터 교체

**작업자**: 윤준수 (BE-A) · **작업일**: 2026-11-17 · **브랜치**: `test` (커밋 8개, 아래 1~8)

**배경**: 교수님 피드백. 기업 → 기관 한 방향이 아니라 기업 ↔ 기업, 기관 ↔ 기관, 대학 ↔ 기업처럼 누구나 장비를 빌려주고 빌리는 플랫폼으로 확장. 시연 데이터도 가상 조직 + 실제 판매 모델 장비로 교체

**설계**: 조직 유형과 권한을 분리. 역할(`COMPANY_USER`/`INSTITUTION_MANAGER`)과 DB 컬럼명(`institution_id`)은 그대로 두고(JWT·FE·테스트 영향 최소화), 권한은 소속 조직으로만 판단. 한 의뢰에서 누가 의뢰자이고 누가 보유 조직인지는 그 의뢰의 `requester_id`·`institution_id`로 정해짐

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
- 기존 기관 9곳은 삭제하지 않고 가상 이름으로 변경(id 유지 → 기존 의뢰·계정 그대로). 대학 부설 2곳은 UNIVERSITY 로
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

*BE-B 영역 (허영재): 윤준수 수정*
- `request/dto/RequestBox`(새): 보낸/받은 의뢰 구분 enum
- `request/controller/TestRequestController`: 목록에 `box` 파라미터, 설명 문구
- `request/service/RequestQueryService`: 목록을 역할 대신 `box`로 분기 (기본값은 기존 동작)
- `request/service/RequestCommandService`: 의뢰 생성의 기업 전용 제한 해제
- `request/service/TestRequestServiceImpl`: 의뢰자 제한을 "관리자 불가"로 바꾸고 자기 조직 장비 의뢰 차단(400)
- `request/service/RequestWorkflow`: 주석·알림 문구("장비 보유 조직에서 보완을 요청했습니다")만. 로직 변경 없음
- `review/service/ReviewService`: 담당자 자동 지정을 기관 담당자 역할 → 보유 조직 구성원 전체로
- `review/dto/ReviewInputs`, `review/controller/ReviewController`, `reservation/controller/ReservationController`: 설명 문구만
- 테스트 `RequestWorkflowTest`: 기관 → 다른 기관 의뢰, 기관 → 기업 장비 의뢰 끝까지, 대학 ↔ 기업 시연 계정 시나리오 추가

*BE-C 영역 (주현욱): 윤준수 수정*
- `chat/service/ChatPersistence`, `chat/service/ChatSessionQueryService`: 상담의 기업 전용 제한 해제 (본인 데이터만 보는 규칙은 그대로)
- `requirement/service/RequirementApplicationService`: 시험조건의 기업 전용 제한 5곳 해제
- `aipipeline/service/PipelineStore`: 자동 추진의 기업 전용 제한 해제
- `aipipeline/service/PipelineRecovery`: 서버 재시작 시 기업·관리자 실행만 복구하던 조건 제거
- `matching/service/MatchingService`: 자기 소속 조직 장비를 추천 후보에서 제외
- 테스트: `PipelineStateTest`, `RequirementServiceTest`(기관 계정의 본인 데이터 허용으로 기대값 변경), `ChatSessionQueryTest`, `ChatSessionQueryIntegrationTest`(대시보드 시그니처 변경 반영), `MatchingServiceTest`(자기 조직 추천 제외 테스트 추가)

*문서*: `README.md`, `docs/api-spec.md`(권한·장비·의뢰·대시보드, BE-B·BE-C 절 포함), `docs/demo-scenario.md`(§1-1 추가, 기관 이름), `docs/design/schema.sql`, `docs/design/erd.dbml`, `docs/design/tools/gen_v8.py`·`equipment_catalog.json`(새), 이 파일

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

**1. 비교표에 비용·기간·가용성 추가** (`POST /equipment/compare`): 시험 실무자 피드백 "일정과 비용을 사전에 비교하기 어렵다"
- 응답에 `usage`(장비별 접근 범위·요금 원문·금액·단위·소요 기간·가동률·가용 상태·기관 확인 상태·정보 갱신일) 추가. 기존 필드는 그대로 (추가만)
- 요청에 선택 `testType` → `usage[].testTypeEstimate`(그 시험의 예상 비용·소요일, 그 장비가 못 하면 `supported=false`), 없는 코드는 400
- seed 장비는 요금·예상 비용이 대부분 비어 있어 null(화면에 "정보 없음"), 기관이 입력하면 채워짐

**3. 정지 계정 즉시 차단** (`global/security` 변경 → 팀 공지 필요)
- `AccountStatusChecker`(global) + `UserAccountStatusCache`(user, 30초 캐시): JWT 필터가 요청마다 계정 상태 확인
- 승인·거절·정지·해제 시 DB 커밋 직후 캐시 삭제 (커밋 전에 지우면 다른 요청이 옛 상태를 다시 캐시할 수 있어서)
- 정지·거절·승인 대기 → 보호된 API 403 `ACCOUNT_NOT_ACTIVE`, 계정 없음 → 401 `AUTH_REQUIRED`, 공개 API 는 그대로. 새 에러 코드 없음
- 이전 기록(3번 덩어리)의 "정지해도 access 토큰은 최대 1시간 유효"는 해결됨. api-spec §0-4·§12, Swagger 설명도 고침

**4. 기존 서비스와의 차이** (README §1, 계획서 개요)
- ZEUS(과기정통부, 국가 R&D 장비)가 2026-07-27 부터 AI 검색 시범 운영(연구 목적 자연어 → 가까운 이용 가능 장비 추천 → 예약, 12월 정식 예정) → "자연어로 장비 찾기"는 차별점이 아님
- 보도(데일리안·아시아경제)에 없는 ZEUS 기능은 단정하지 않음. ASTRA 차별점은 조건별 충족·미충족 근거, 사양·요금·기간 비교, 의뢰서 → 기관 검토·보완 → 예약 흐름, 시연 데이터·기관 확인 표시
- 발표 때 "자연어 검색"보다 "조건별 근거 + 시험 의뢰 흐름"을 강조하는 게 좋음

**확인**
- 테스트 188개 통과 (새로 4개: 비교표 이용 조건 2, 즉시 차단 2), BE-C 88개 포함
- 실제 서버: 비교표 `usage`·`testTypeEstimate`(48 가능, 37 불가), 없는 testType 400 / 정지 직후 같은 토큰 403 (0.07초), 해제 직후 200, 공개 API 200, 계정 없는 토큰 401 (확인용 계정은 원래 상태로 되돌림)

**커밋**: `e627028`(비교표) `be1fa39`(즉시 차단) `406ef9e`(문서) · push 완료, PR 대기

**바뀐 파일**: `equipment/dto/EquipmentInputs.java`, `equipment/dto/EquipmentManageResponses.java`, `equipment/service/EquipmentCompareService.java`, `equipment/controller/EquipmentCompareController.java`, `global/security/AccountStatusChecker.java`(새), `global/security/JwtAuthenticationFilter.java`, `global/config/SecurityConfig.java`, `user/service/UserAccountStatusCache.java`(새), `user/service/UserService.java`, `user/repository/UserRepository.java`, `admin/controller/AdminUserController.java`, 테스트 3개(`EquipmentApiTest`, `EquipmentManageTest`, `AdminUserApprovalTest`), `README.md`, `docs/api-spec.md`, 이 파일

---

## 2026-11-09 · BE-C 병합 후 통합 점검 + SSE 끊김 버그 수정

**상황**: PR #9 (BE-C: AI 상담·시험조건·매칭·자동 추진) main 병합 → `feature/be-a` 에 fast-forward 로 받음. 합친 코드 전체 테스트 182개 통과 (BE-A 94 + BE-C 88)

**BE-C 가 BE-A·공통 파일에서 바꾼 것** (확인함, 문제 없음)
- `ChatSessionQuery` 주석 보충(시그니처 그대로), `DashboardSearchApiTest` 의 `chats` 기대값 true 로 (상담 연결됨)
- `application.yml`·`.env.example` 에 `AI_PROVIDER`(기본 gemini), `GEMINI_API_KEY`, `GEMINI_MODEL`(기본 `gemini-3.8-flash`). team-guide 는 Claude 기준이라 시연 때 어느 쪽을 쓸지 팀 합의 필요

**찾아서 고친 버그 (BE-A, `SecurityConfig`)**: 로그인이 필요한 SSE 스트림(AI 상담 스트리밍)이 마지막에 끊김
- 원인: `SseEmitter` 는 응답을 마칠 때 비동기 재요청(ASYNC dispatch)을 하는데 JWT 필터는 그때 다시 돌지 않음 → Spring Security 가 비로그인으로 보고 `Access Denied` → 응답이 이미 나가는 중이라 연결이 끊김 (로그: "response is already committed")
- 수정: `auth.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()` (원래 요청에서 이미 인증·권한 통과)
- `SseAsyncDispatchTest` 추가: LLM 없이 같은 구조의 테스트용 SSE 로 확인. 수정 전 코드로는 실패, 수정 후 통과하는 것을 직접 확인
- BE-C 테스트는 실제 서버(비동기 재요청)를 거치지 않아 드러나지 않았던 것으로 보임

**실제 서버 통합 점검 (Gemini)**
- `gemini-3.8-flash`(기본값): Google 쪽 503 UNAVAILABLE "high demand" (일시적 과부하) → 모든 AI 단계 502. 키는 정상 (모델 목록 조회됨)
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
2. Gemini 실패 시 HTTP 상태·오류 메시지를 로그에 남기기 (키 제외). 지금은 원인을 알 수 없음
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
  - 기본 `http://localhost:5173`. `.env.example` 을 그대로 복사해 빈 값이 들어가도 기본 주소를 씀 (`${이름:기본값}` 은 빈 문자열일 때 기본값을 안 쓰기 때문)
  - 메서드 GET·POST·PUT·PATCH·DELETE·OPTIONS, 헤더 전부 허용, `Content-Disposition` 노출, credentials 안 씀(쿠키 없음, Bearer 헤더), 사전 요청 캐시 1시간
- `SecurityConfig`: `http.cors(...)` → Spring Security 앞단에서 처리되어 401·403 응답에도 CORS 헤더 (FE 가 401 을 읽고 로그인 화면으로 보낼 수 있음)
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

**팀 공지**: (FE 대상) 실제 서버 연결 시 기본 허용 주소는 `http://localhost:5173`, 다른 주소면 알려 주거나 서버 `.env` 의 `CORS_ALLOWED_ORIGINS` 에 추가. 쿠키 없이 `Authorization` 헤더만 쓰면 됨

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
- ~~정지한 계정의 access 토큰은 만료(최대 1시간)까지 유효~~ → 2026-11-09 해결 (다음 요청부터 바로 403)
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

**DB**: `V5__fill_institution_region.sql`: 원본에 지역이 없던 진주뿌리기술지원센터와 장비 31대에 '진주' 채움

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
- `V3__demo_accounts.sql`: 시연 계정 5개 (admin, company, manager1, manager2, pending `@astra.demo`)
- 에러 코드 3개 추가: `INVALID_CREDENTIALS`, `EMAIL_ALREADY_EXISTS`, `ORGANIZATION_ALREADY_EXISTS`
