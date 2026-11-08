# BE-C 작업 기록 (주현욱)

AI 상담·시험조건·규칙 기반 매칭·AI 의뢰서 초안·자동 추진 작업을 덩어리별로 기록합니다. 최신 작업이 위에 옵니다.
API 형식은 [`api-spec.md`](api-spec.md), 담당·일정은 [`team-guide.md`](team-guide.md) §5 를 봅니다.
✅는 코드·명시한 테스트 범위의 완료입니다. 실제 외부 LLM·DB·BE-B 통합 완료를 의미하지 않습니다.
원격 반영 표시는 로컬에 저장된 추적 참조 기준입니다. 이 작업에서는 fetch·push·PR을 실행하지 않았습니다.

| 덩어리 | 내용 | 상태 | main 반영 |
|---|---|---|---|
| 1 | LLM 연동·AI 상담·시험조건 관리 | ✅ / 실제 LLM·DB 확인 ⏳ | main 미반영 / `0dcc6c8`, `origin/feature/be-c`에 포함 |
| 2 | 규칙 기반 장비 매칭·조건 비교표·이력 | ✅ / 실제 DB 확인 ⏳ | main 미반영 / `dddca6b`, `origin/feature/be-c`에 포함 |
| 3 | AI 의뢰서 content 생성·BE-B 저장 계약 호출 | 🚧 BE-B 구현·결과 조회 계약 대기 | main 미반영 / `d91cbb0`, `origin/feature/be-c`에 포함 |
| 4 | AI 자동 추진 시작·조회·대기·재개·취소 | 🚧 DRAFT 완료 확인 대기 / 진행 SSE 선택 기능 미구현 | main 미반영 / `d91cbb0`, `origin/feature/be-c`에 포함 |
| 5 | 대시보드 최근 상담 조회·활동 시각 연동 | ✅ / 실제 DB 확인 ⏳ | 로컬 `4be5183` (게시 안 함) |
| 6 | Gemini 임시 테스트 어댑터·Claude 선택 설정 | ✅ 대역 검증 / 실제 Gemini 호출 ⏳ | 로컬 `9c236d7` (게시 안 함) |

---

## 2026-11-08 · 6번 덩어리: Gemini 임시 테스트 연동

**왜**: 팀의 기본 제공자는 Claude지만, 임시로 Gemini를 사용해 같은 BE-C 흐름을 테스트하려는 요청. 기존 Claude 구현을 보존하고 제공자 경계에서 교체

**한 것**
- `llm/client/GeminiLlmClient`: 기존 `LlmClient` 구현. JDK HTTP 클라이언트로 Gemini Developer API의 `generateContent`·`streamGenerateContent` 호출. 의존성 추가·업그레이드 없음
- 시스템 프롬프트·사용자 입력·JSON Schema 전달, Gemini 3.x `thinkingLevel` 사용. 기존 조건 추출·단위·근거·추천 판정 검증은 그대로 적용
- 일반 응답과 SSE의 정상 `STOP` 확인, 실제 모델·응답 ID·토큰 사용량 반환. thought 부분은 사용자에게 전달하지 않음
- 키 미설정·비정상 HTTP·차단·잘린 응답·잘못된 JSON·중간 종료·45초 제한·취소를 명시적인 오류로 처리. 가짜 응답이나 Claude 자동 fallback 없음
- `AnthropicLlmClient`: 기존 무조건 등록 어노테이션은 주석으로 보존하고 조건부 등록을 추가. Claude 호출 로직은 그대로 유지
- `application.yml`의 `astra.ai`에 제공자·Gemini 설정 추가, `.env.example`에 빈 키와 선택값 추가. 임시 기본 제공자는 Gemini, Claude의 기존 키·모델 설정은 보존
- 실제 `.env`와 비밀값은 읽거나 변경하지 않음

**확인**
- `./gradlew test --tests 'kr.astra.llm.*' --tests 'kr.astra.chat.*' --console=plain`: **47개 통과**, 전체 소스·테스트 컴파일 성공
- 신규 Gemini 로컬 HTTP 대역 테스트 7개: 요청 스키마·메타데이터, SSE 공백·thought 제외, 잘림·차단·JSON·HTTP 오류, 스트림 종료·연결 중단, 키 누락, 헤더 수신 후 본문 타임아웃, 중단 시 interrupt 보존
- 제공자 선택 테스트 3개: Gemini 단일 Bean, Claude 복귀·기존 설정 호환, 잘못된 제공자 값에서 자동 fallback 금지
- 기존 Claude HTTP 대역 및 상담·조건 추출·설명·초안 테스트 포함. 실제 유료 API 호출·실제 Gemini 품질 평가는 하지 않음

**남은 것·주의**
- 실제 Gemini 키와 모델 사용 권한·할당량을 가진 환경에서 상담·조건 추출 결과 확인 필요
- Gemini 어댑터는 Gemini 3.x의 `thinkingLevel`을 사용하므로, 다른 세대 모델로 바꿀 때는 해당 설정 호환성 확인 필요
- DB 통합 검증은 기존 Docker 환경 문제로 미완료. 이번 변경은 DB·Flyway를 변경하지 않음

**참고**: [Google 생성 API](https://ai.google.dev/api/generate-content?hl=en), [Gemini 3.8 Flash 모델](https://aistudio.google.com/docs/models/gemini-3.8-flash)

**커밋**: `9c236d7` (로컬, push·PR 안 함)

**바뀐 파일**: `llm/client/AnthropicLlmClient.java`, `llm/client/GeminiLlmClient.java`(새), `application.yml`, `.env.example`, `GeminiLlmClientTest`·`LlmProviderSelectionTest`(새), 이 파일. 공용 설정은 `astra.ai`와 AI 환경변수 예시만 추가

---

## 2026-11-08 · 5번 덩어리: 대시보드 최근 상담 조회·활동 시각 연동

**왜**: BE-A가 추가한 `ChatSessionSummary`·`ChatSessionQuery`는 대시보드용 내부 계약. 기존 상담 목록 API와 충돌하지 않지만 구현체가 없어 최근 상담이 비어 있었고, 메시지를 저장해도 세션의 `updatedAt`이 바뀌지 않았음

**한 것**
- `ChatSessionQueryService implements ChatSessionQuery`: 인증된 기업 본인 또는 ADMIN 권한 확인, 사용자별 요약 조회, `updatedAt DESC, id DESC`, 조회 개수 제한
- 기존 대시보드 `ObjectProvider<ChatSessionQuery>`로 자동 연결. 목록이 없어도 `dataAvailable.chats=true`
- 기존 상담 목록 API의 전체 응답·생성일 정렬은 유지. 요약 DTO·공개 메서드 시그니처·새 엔드포인트 추가 없음
- 메시지·상담 조건 저장 성공 시 같은 트랜잭션에서 세션 활동 시각 갱신. 권한 거절·중복 전송은 갱신하지 않음
- BE-A 운영 코드 변경 없음. 기존 `DashboardSearchApiTest`의 상담 미연결 기대값·설명만 연결 완료에 맞춤

**확인**
- `./gradlew test --tests 'kr.astra.chat.*' --console=plain`: **18개 통과** (기존 8 + 조회·활동 시각 10)
- Spring 컨텍스트에서 구현체가 하나만 등록되고 대시보드가 요약과 연결 여부를 받는 것을 대역 검증
- `./gradlew test --tests 'kr.astra.ChatSessionQueryIntegrationTest' --tests 'kr.astra.DashboardSearchApiTest.기업_요약*' --console=plain`: 3개 모두 Docker 환경 부재로 컨텍스트 초기화 실패. DB 정렬·제한·활동 시각 저장 및 실제 HTTP는 미검증

**커밋**: `4be5183` (로컬, push·PR 안 함)

**바뀐 파일**: `chat/service/ChatSessionQueryService.java`(새), `ChatSessionQuery.java`, `ChatPersistence.java`, `chat/repository/ChatSessionRepository.java`, `ChatSessionQueryTest`·`ChatActivityTest`·`ChatSessionQueryIntegrationTest`(새), `DashboardSearchApiTest`

---

## 2026-11-08 · 3·4번 덩어리: AI 의뢰서 초안 경계·자동 추진

**API**
- `POST /test-requests/ai-draft`: 입력·소유권·장비 시험유형·매칭 연결·스냅샷 일치 검증. 최종 성공 응답은 BE-B 연동 대기
- `POST /ai/auto-runs` (202), `GET /ai/auto-runs/{id}`, `POST .../{id}/resume`, `POST .../{id}/cancel`

**한 것**
- `AiDraftService`: 알려진 신청자·장비·시험조건으로 content 구성, LLM으로 메모·확인사항 생성. 제공되지 않은 일정·규격은 사실처럼 채우지 않음
- 기존 `TestRequestService.createDraft(CreateDraftCommand) → Long` 호출과 `aiGenerated=true` 전달 구현·대역 검증. 제출·검토·예약 호출 없음
- 직접 초안 API는 결과 조회 계약이 없는 상태에서 저장 후 실패하는 고아 초안을 만들지 않도록 저장 전에 오류 반환. 응답 명세 축소·가짜 requestNo/revision 없음
- 자동 추진 ANALYZE → SEARCH → MATCH → DRAFT 단계와 결과 저장, 필수값 누락·미확인 시 `WAITING_USER`, 같은 실행 재개, 취소·단계 중복 실행 방지
- 단계 부작용과 체크포인트를 같은 트랜잭션에 저장. 재시작 시 PENDING/RUNNING 복구 경로 구현
- 초안 ID만으로 DRAFT·AI revision을 확인했다고 처리하지 않음. ID만 반환되면 보존 후 `FAILED`, 확인 없이 `SUCCEEDED`로 전환하지 않음

**확인**
- 당시 BE-C 선택 검증 **62개 통과**: 상담·조건·매칭·LLM·초안·자동 추진 범위
- `BeCPersistenceTest` 4개는 Docker 환경 부재로 초기화 실패. 실제 DB 잠금·롤백·매핑 및 재시작 복구 미검증
- 이후 사용자 작업으로 `PipelineWorkerTest.java`·`BeCPersistenceTest.java`가 작업 트리에서 삭제됨. 해당 삭제를 복원하거나 후속 커밋에 포함하지 않았음. 62개는 삭제 이전 검증 기록임

**남은 것·다른 담당자와 맞출 것**
- BE-B: `createDraft` 실제 구현과 requestNo·실제 상태·revisionNo·AI 생성 여부·content를 확인할 공개 결과/조회 계약 필요. BE-B 패키지·시그니처는 변경하지 않음
- BE-B 저장이 같은 트랜잭션에 참여하는지 확인 필요. 별도 트랜잭션·외부 저장이라면 중복 방지 계약 필요
- 자동 추진 진행 SSE `/events`는 선택 기능으로 미구현. 현재는 상태 조회 사용
- 명세의 변경 요청 version과 기존 BE-C 테이블 구조 불일치: 행 잠금은 적용했으나 클라이언트 버전 검증은 스키마·계약 합의 필요
- 기관의 의뢰 조회 과정에서 `RequirementService.getSnapshot()`을 호출할 권한 위임 정책은 BE-B와 확인 필요

**커밋**: `d91cbb0` (현재 `origin/feature/be-c` 추적 참조에 포함)

**바뀐 파일**: `aipipeline/` 컨트롤러·DTO·엔티티·Repository·서비스, `llm/` AI 초안 컨트롤러·DTO·서비스, `prompts/draft-request.md`, 대응 테스트. BE-B 저장 엔티티·Repository 복제 없음

---

## 2026-11-07 · 2번 덩어리: 규칙 기반 매칭·비교·이력

**API**: `POST /requirements/{id}/match-runs`, `GET /match-runs/{id}`, `GET /requirements/{id}/match-runs`

**한 것**
- BE-A `TestTypeService.getConditionRules()`의 기존 규칙 DTO와 공개 장비 서비스를 사용. `test_condition_rules` 엔티티 중복 생성·규칙 수정·타 도메인 Repository 직접 접근 없음
- `RANGE_WITHIN`: 범위 포함, `MAX_LIMIT`: 요구값 ≤ 장비 상한, `MIN_LIMIT`: 장비값 ≤ 요구값 (작을수록 좋은 분해능 등), `NONE`: 기관 확인 필요
- 요구값·사양 누락, 미확인, 비교 불가능한 값·단위는 충족으로 판정하지 않음. 호환 단위 환산, REAL 우선 및 MOCK 사용 표시
- 분모는 필수 조건 + 제공된 선택 조건, 충족 비율을 소수점 둘째 자리 반올림. NONE은 UNKNOWN으로 분모에 포함, 미제공 선택 조건은 제외
- 종합 판정 → 점수 내림차순 → 장비 ID 오름차순으로 순위 고정
- 요구조건 스냅샷·`rule-v1` 알고리즘 버전·조건별 근거·결과·이력 저장. 소유권 및 선택 결과의 최신 조건 일치 검증
- LLM 설명이 코드의 종합 판정·장비 ID·조건별 판정 집합을 뒤집으면 오류 처리

**확인**: 비교 방향·경계값·단위·누락·REAL/MOCK 우선순위 및 서비스 연결 검증은 당시 BE-C 62개 선택 테스트에 포함. 실제 PostgreSQL 이력 저장은 환경 문제로 미검증

**커밋**: `dddca6b` (현재 `origin/feature/be-c` 추적 참조에 포함)

**바뀐 파일**: `matching/` 컨트롤러·DTO·엔티티·Repository·서비스, `llm/service/MatchExplanationService`, 대응 프롬프트·테스트

---

## 2026-11-07 · 1번 덩어리: LLM·AI 상담·시험조건 관리

**API**
- 상담 세션 생성·목록, 메시지 조회·전송·저장, JSON/SSE 응답, 대화에서 시험조건 생성·갱신
- 시험조건 직접 생성·조회·기본 정보 수정·조건 일괄 저장·분석·추가 질문·확정
- BE-B용 기존 `RequirementService.getSnapshot()` 구현

**한 것**
- Anthropic SDK 사용, structured outputs·effort·server-side fallback, 실제 모델·요청 ID·토큰 및 프롬프트/응답 metadata 처리
- 구조화 JSON의 중복 필드·후행 값·허용되지 않은 키·잘못된 타입·단위·근거 검증. 사용자 조건을 AI가 덮어쓰지 않도록 보호
- 시험유형 규칙에 따른 조건 추출, 필수 누락·미확인 분석, DRAFT 수정·확정 상태 검사
- 인증 정보 기반 소유권·ADMIN 정책 적용, 상담 동시 전송 제어, 실패한 응답을 정상 저장하지 않음
- SSE `delta`·`done`·`error`, timeout·연결 중단 처리. 키 미설정·외부 실패를 가짜 성공으로 대체하지 않음

**확인**: 전체 소스 컴파일 성공. 상담·SSE·권한·조건 검증·LLM 파싱·실패 처리를 대역으로 검증 (당시 BE-C 62개 선택 테스트에 포함). 실제 유료 LLM·DB·Swagger 수동 확인은 하지 않음

**남은 것·주의**
- 재사용 가능한 `ConditionExtractionService`는 구현했으나, 이후 BE-A가 추가한 `requirement/service/ConditionExtractor` 계약 어댑터는 아직 없음. `/search`는 현재 키워드 해석 경로이며 AI 연동 완료로 보고하지 않음
- SSE 이벤트명은 프론트와 확인 필요

**커밋**: `0dcc6c8` (현재 `origin/feature/be-c` 추적 참조에 포함)

**바뀐 파일**: `chat/`, `requirement/`, `llm/`, `resources/prompts/`, 대응 테스트. 기존 공통 응답·예외·인증 구조 재사용
