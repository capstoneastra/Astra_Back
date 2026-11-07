# ASTRA 기술 스택 · 협업 규칙 · 역할 분담 v0.1

> 원칙: 캡스톤 시연이 목표입니다. 익숙하고 자료가 많은 기술을 쓰고, 규칙은 충돌을 막는 데 필요한 만큼만 둡니다.

---

## 1. 기술 스택

### 백엔드
| 구분 | 선택 | 이유 |
|---|---|---|
| 언어 | **Java 21 (LTS)** | 자료·라이브러리가 가장 많고 팀원 설치 문제가 적음. 가상 스레드 정식 지원 |
| 프레임워크 | **Spring Boot 4.1.x** (현재 4.1.2) | 무료 보안 지원이 2027년 7월까지라 캡스톤 기간을 덮음. 3.5는 2026년 6월에 무료 지원 종료, 4.0은 2026년 12월 종료 |
| 빌드 | **Gradle 9.x** (Wrapper 9.8.2 고정, Java 툴체인 21) | 각자 설치한 Gradle 말고 `./gradlew`로만 빌드 |
| DB | **PostgreSQL 16** | 설계(`schema.sql`)가 PostgreSQL 기준 (JSONB, pg_trgm) |
| ORM | Spring Data JPA (+ 검색만 native query) | JSONB 컬럼은 `@JdbcTypeCode(SqlTypes.JSON)` |
| DB 변경 관리 | **Flyway** | `schema.sql` → `V1__init.sql`, `seed.sql` → `V2__seed.sql` |
| 인증 | Spring Security 7 + JWT (jjwt 0.13.0), access 토큰(1시간) + refresh 토큰(14일) | refresh 토큰은 해시만 `refresh_tokens`에 저장하고, 재발급할 때마다 새 토큰으로 교체 |
| API 문서 | **springdoc-openapi 3.2.0 (Swagger UI)**, Spring Boot 4 지원 계열 | 코드가 곧 명세. FE는 `/swagger-ui`에서 바로 확인·호출 |
| AI | **Anthropic Java SDK** `com.anthropic:anthropic-java:3.6.0`, 모델 `claude-opus-5-5` | 공식 SDK. 조건 추출은 structured outputs(JSON 스키마)로 받음 |
| 테스트 | JUnit 5 + **Testcontainers (PostgreSQL 16)** | 테스트마다 실제 DB를 띄워 마이그레이션·제약조건까지 검증 (Docker 필요) |
| 기타 | Lombok, Bean Validation, Actuator(헬스체크) | |

#### 버전 맞추기
- 프로젝트는 start.spring.io에서 **Spring Boot 4.1.x / Gradle - Groovy / Java 21**로 생성합니다.
- `build.gradle`에 툴체인을 지정해서, PC에 다른 Java(예: 25)가 깔려 있어도 모두 21로 빌드되게 합니다.

```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

- IntelliJ: Project SDK와 Gradle JVM을 모두 21로 맞춥니다.

#### Spring Boot 4 주의사항
- 인터넷 강의·블로그 대부분이 Spring Boot 3 기준입니다. 4는 **Spring Security 7, Jackson 3** 등 바뀐 부분이 있어서 예제를 그대로 붙이면 컴파일이 안 될 수 있습니다. 공식 문서와 마이그레이션 가이드를 먼저 확인하세요.
- 외부 라이브러리 버전은 `build.gradle`의 `ext { }` 한 곳에 고정되어 있습니다 (뼈대에서 빌드·테스트·실행까지 확인한 버전). 바꿀 때는 공지 후 PR로 합니다.

### 프론트엔드 (연지 · 홍인)
| 구분 | 선택 |
|---|---|
| 기본 | **React 18 + TypeScript + Vite** |
| 라우팅 / 서버 상태 | React Router, TanStack Query, axios |
| 스타일 | Tailwind CSS |
| 목서버 | **MSW** (백엔드 API 완성 전까지 `api-spec.md` 예시 응답으로 개발) |
| 차트 | Recharts (시뮬레이션 그래프, 대시보드) |

### 실행 환경
- 로컬: Docker Desktop을 켜고 `./gradlew bootRun` (또는 IntelliJ 실행) → `compose.yaml`의 PostgreSQL 16이 자동으로 뜨고 Flyway가 V1·V2를 적용. Swagger: http://localhost:8080/swagger-ui.html
- 테스트: `./gradlew test` (Testcontainers가 테스트용 DB를 따로 띄움)
- 시연: 클라우드 VM 1대에 PostgreSQL + `SPRING_PROFILES_ACTIVE=prod`로 jar 실행. 안 되면 노트북 로컬 시연
- **API 키(`ANTHROPIC_API_KEY`), DB 비밀번호는 절대 커밋 금지.** `.env.example`만 올리고 실제 값은 팀 채팅으로 공유

### AI 사용 기준 (BE-C 참고)
| 용도 | 방식 |
|---|---|
| 상담 채팅 | 스트리밍 응답 → SSE로 프론트 전달, effort `low` |
| 조건 추출·누락 분석 | structured outputs로 JSON 강제 (`requirement_conditions` 형태). 키는 해당 시험유형의 `test_condition_rules` 목록 안에서만 추출, effort `medium` |
| 추천 이유 문장 | 매칭은 규칙 기반 코드가 판단하고, LLM은 결과를 문장으로만 설명 |
| 의뢰서 초안 | 조건 + 장비 정보로 `content` JSON 생성 (structured outputs) |
- 거절 대비: 요청에 `fallbacks: "default"`(server-side fallback beta) 설정
- 프롬프트·응답 원문은 `chat_messages.metadata`에 저장 (디버깅용)
- 비용: 팀 공용 키 1개, 콘솔에서 월 사용 한도 설정

---

## 2. 저장소 구조

GitHub Organization `capstoneastra` 아래 **백엔드·프론트엔드 저장소를 따로** 둡니다. 백엔드: https://github.com/capstoneastra/Astra_Back

백엔드는 **도메인별로 먼저 나누고, 그 안을 계층별로** 나눕니다. 계층별로 먼저 나누면 3명이 같은 `controller/` 폴더를 동시에 고쳐서 충돌이 잦습니다.

```
Astra_Back/
├── build.gradle            의존성·버전 (ext { } 에서 버전 관리)
├── compose.yaml            로컬 PostgreSQL 16 (bootRun 시 자동 실행)
├── .env.example            환경변수 예시 (.env 는 커밋 금지)
├── docs/
│   ├── api-spec.md, team-guide.md
│   └── design/             schema.sql, seed.sql, erd.dbml, tools/ (seed·ERD 생성기)
└── src/
    ├── main/
    │   ├── resources/
    │   │   ├── application.yml / application-prod.yml
    │   │   ├── db/migration/   V1__init.sql (schema.sql), V2__seed.sql (seed.sql)
    │   │   └── prompts/        LLM 프롬프트 텍스트 (BE-C)
    │   └── java/kr/astra/
    │       ├── global/         config, security, response, exception, entity, mail   ← BE-A (모두 사용)
    │       ├── auth/ user/ organization/ admin/ equipment/ testtype/ dashboard/   ← BE-A
    │       ├── request/ review/ reservation/ notification/ simulation/           ← BE-B
    │       └── chat/ requirement/ matching/ aipipeline/ llm/                     ← BE-C
    └── test/java/kr/astra/     DB 스키마·제약조건 테스트, API 규약 테스트
```

프론트엔드는 별도 저장소에서 `api`, `mocks`(MSW), `pages`, `components`, `hooks`, `types`, `constants` 구조로 시작합니다.

도메인 하나의 내부 구조 (모든 도메인 동일):

```
equipment/
├── controller/   EquipmentController
├── service/      EquipmentService, EquipmentQueryService (다른 도메인에 제공)
├── repository/   EquipmentRepository
├── entity/       Equipment, EquipmentSpec, enum
└── dto/          ~Request, ~Response
```

- **도메인 패키지 = 담당자.** 다른 사람 패키지를 고쳐야 하면 그 사람에게 말하고 PR 리뷰를 받습니다.
- `global/`은 모두가 쓰니까 BE-A가 1주차에 먼저 만들고, 이후 변경은 BE 3명 모두에게 공지합니다.
- 다른 도메인 기능은 그 도메인의 `service`만 호출합니다. 남의 repository를 직접 쓰지 않습니다.
- **뼈대(폴더 구조, Gradle, Flyway, `global/`, 빈 도메인 패키지, 내부 인터페이스 껍데기)는 BE-A가 만들어 `main`에 먼저 푸시**하고, 그 뒤로는 모두 PR로만 `main`에 병합합니다.

---

## 3. 협업 규칙

### 브랜치 (GitHub Flow: `main` 하나 + 기능 브랜치)
| 브랜치 | 용도 |
|---|---|
| `main` | 유일한 기본 브랜치이자 **최종 결과물**. 항상 빌드·테스트가 통과하는 상태를 유지 |
| `feature/#12-equipment-search` | 이슈 하나당 브랜치 하나, `main`에서 따서 `main`으로 PR (`fix/`, `refactor/` 도 같은 형식). 병합 후 삭제 |

- `main`에 직접 push 금지. 모든 변경은 PR로만 병합
- PR은 **GitHub Actions 빌드(테스트 포함) 통과 + 리뷰 1명 승인 후 Squash merge**. 빌드가 빨간색이면 병합하지 않습니다
- PR은 작게: 하루 이상 쌓지 말고 API 1~2개 단위로 올리기. 매일 아침 `git pull`로 `main` 최신 내용을 받고, PR 전에 `main`을 내 브랜치에 merge해서 충돌을 내가 먼저 해결
- **발표·중간점검 버전은 태그로 표시**: 시연 시나리오를 처음부터 끝까지 확인한 커밋에 `v0.1-midterm`, `v1.0-final`처럼 태그를 붙입니다. 시연은 태그 버전으로 합니다

```bash
git switch main && git pull                      # 최신 main 받기
git switch -c feature/#12-equipment-search       # 기능 브랜치 만들기
# ... 작업, 커밋 ...
git fetch origin && git merge origin/main         # PR 전에 최신 main 반영
git push -u origin feature/#12-equipment-search  # GitHub 에서 main 으로 PR
```

### 커밋 메시지
`타입: 내용` (한글 가능)
```
feat: 장비 목록 검색 API 추가
fix: 보완 요청 시 상태 검증 누락 수정
refactor: 매칭 점수 계산 분리
docs: API 명세 예약 섹션 수정
chore: Flyway 설정 추가
```

### 이슈 · 작업 관리
- GitHub Projects 보드: `Todo → In Progress → Review → Done`
- 이슈 단위: **API 1개 또는 화면 1개**. 제목 예: `[BE] GET /equipment 검색`, `[FE] 장비 상세 화면`
- 라벨: `BE`, `FE`, `AI`, `bug`, 우선순위 `P0`(시연 필수) / `P1` / `P2`

### API 변경 규칙 (가장 중요)
1. 요청/응답 형식을 바꾸면 **병합 전에** 팀 채팅 `#api` 방에 공지
2. Swagger 어노테이션도 같이 수정
3. enum 값 추가·변경은 FE에 반드시 알림 (화면 라벨 매핑이 깨짐)

### DB 변경 규칙
- 테이블 수정은 **새 Flyway 파일로만**: `V3__add_equipment_image.sql`
- 이미 병합된 마이그레이션 파일은 절대 수정하지 않기 (다른 사람 DB가 깨짐)
- 번호가 겹치지 않게 만들기 전에 채팅에 "V5 씁니다" 한 줄 남기기

### 코드 규칙 (최소한만)
- 응답은 공통 래퍼 `ApiResponse<T>` 사용 (`api-spec.md` §0-1 형식)
- 예외는 `BusinessException(ErrorCode.XXX)`로 던지고 전역 핸들러에서 변환
- Controller → Service → Repository 3계층. Entity를 그대로 응답하지 말고 DTO 사용
- 포맷은 IntelliJ 기본 포매터, FE는 ESLint + Prettier

### 완료 기준
- [ ] Swagger에서 호출해서 정상 응답 확인
- [ ] seed 데이터로 실제 동작 확인 (권한별로 한 번씩)
- [ ] PR 리뷰 승인
- [ ] 이슈를 Done으로 이동

### 회의
- **매일 비동기 스탠드업**: 팀 채팅에 "어제 한 것 / 오늘 할 것 / 막힌 것" 3줄
- **주 1회 정기 회의 (30분)**: 진행률 확인, 다음 주 이슈 배정, 통합 테스트
- 막히면 30분 이상 혼자 붙잡지 말고 채팅에 바로 공유

---

## 4. 백엔드 역할 분담

**팀 구성**: 백엔드 윤준수 · 허영재 · 주현욱 / 프론트엔드 연지 · 홍인 (프론트엔드는 별도 저장소)

| 역할 | 담당자 | 맡는 것 |
|---|---|---|
| **BE-A** · 플랫폼 기반 | **윤준수** | 프로젝트 셋업, 공통 모듈(`global/`), 로그인·권한, 장비 데이터, 관리자, 대시보드 |
| **BE-B** · 의뢰 워크플로우 | **허영재** | 시험 의뢰·상태 관리, 기관 검토·보완, 예약, 알림, 가상 시뮬레이션 |
| **BE-C** · AI | **주현욱** | LLM 연동, AI 상담, 조건 분석, 매칭, 의뢰서 초안, 자동 추진 |

| | BE-A · 플랫폼 기반 (윤준수) | BE-B · 의뢰 워크플로우 (허영재) | BE-C · AI (주현욱) |
|---|---|---|---|
| **핵심 책임** | 프로젝트 셋업, 공통 모듈, 인증/권한, 장비 데이터 | 의뢰 상태머신, 기관 검토·보완, 예약, 알림 | LLM 연동, 상담, 조건 분석, 매칭, 의뢰서 초안, 자동 추진 |
| **담당 기능** | 1, 2, 3, 4, 12, 14 + 회원가입·관리자·기관 장비 관리 | 9, 10, 11, 15 + 알림 | 5, 6, 7, 8, 13 |
| **담당 테이블** | organizations, users, refresh_tokens, equipment, equipment_specs, equipment_change_logs, test_types, equipment_test_types | test_requests, test_request_revisions, request_status_history, supplement_requests, attachments, reservations, notifications, simulation_* | chat_*, test_requirements, requirement_conditions, test_condition_rules, match_*, ai_pipeline_runs |
| **API 섹션** | §1, §2, §10(대시보드), §12(관리자) | §5(AI 초안 제외), §6, §7, §9, §10(알림) | §3, §4, §5 ai-draft, §8 |

### 서로 제공해야 하는 내부 인터페이스 (1주차에 시그니처부터 합의)
| 제공 | 사용 | 메서드 (예시) |
|---|---|---|
| BE-A | 전원 | `CurrentUser` (`@AuthenticationPrincipal`로 로그인 사용자 id·role·orgId) |
| BE-A | BE-C | `EquipmentQueryService.findCandidates(testTypeCode, region)`, `getSpecs(equipmentId)` |
| BE-A | BE-C | `TestTypeService.getConditionRules(testTypeCode)` (조건 규칙. `TestConditionRule` 엔티티는 BE-A가 `testtype` 패키지에 읽기 전용으로 만들어 둠, 따로 만들지 말 것) |
| BE-C | BE-B | `RequirementService.getSnapshot(requirementId)` (의뢰서에 조건 반영) |
| BE-B | BE-C | `TestRequestService.createDraft(userId, equipmentId, requirementId, content, aiGenerated)` (AI 초안·자동 추진) |
| BE-B | 전원 | `NotificationService.notify(userId, type, title, linkPath)` |

> 인터페이스와 DTO만 먼저 만들어 두고 구현은 각자 채웁니다. 그래야 서로 기다리지 않습니다.

> 사람별 할 일, 담당 API 목록, 주의할 점은 5번에 있습니다.

### 우선순위
- **P0 (시연 필수)**: 로그인, 장비 검색·상세, AI 상담 → 조건 정리 → 매칭·비교 → 의뢰서 초안 → 제출 → 기관 보완 요청 → 보완 제출 → 예약 확정
- **P1**: 대시보드, 알림, 시험정보 안내, 기관 장비 수정, 시뮬레이션
- **P2**: 중복 장비 조회, 자동 추진, 관리자 승인 화면, 첨부파일

일정이 밀리면 P2부터 뺍니다. P0는 6주차 전에 반드시 한 번 이어져야 합니다.

---

## 5. 백엔드 담당자별 상세

| | BE-A · 플랫폼 기반 (윤준수) | BE-B · 의뢰 워크플로우 (허영재) | BE-C · AI (주현욱) |
|---|---|---|---|
| API 수 | 39개 (P0 12 · P1 14 · P2 13) | 33개 (P0 11 · P1 16 · P2 6) | 20개 (P0 11 · P1 3 · P2 6) |
| 성격 | 조회·CRUD 위주, 1주차 셋업 부담이 큼 | 상태 관리와 트랜잭션이 핵심 | API는 적지만 LLM·매칭 로직이 어려움 |

> 우선순위: **P0** 시연 필수 · **P1** 있으면 좋음 · **P2** 일정이 남으면. 각 API 표는 우선순위 순서입니다.

### BE-A · 플랫폼 기반 — 윤준수

로그인과 장비 데이터를 맡습니다. 나머지 두 사람이 이 사람이 만든 기반 위에서 작업하므로 1주차 셋업이 가장 중요합니다.

- **담당 패키지**: `global/`, `auth/`, `user/`, `organization/`, `admin/`, `equipment/`, `testtype/`, `dashboard/`
- **담당 테이블**: organizations, users, refresh_tokens, equipment, equipment_specs, equipment_change_logs, test_types, equipment_test_types

#### 다른 담당자와 주고받는 것

- **제공**: `CurrentUser`·권한 체크 유틸 (전원), `EquipmentQueryService.findCandidates()`·`getSpecs()` (BE-C), 공통 `ApiResponse`·`ErrorCode`·`BusinessException` (전원)
- **받음**: `NotificationService.notify()` (BE-B, 가입 승인 알림), 문장 → 조건 추출기 (BE-C, `/search` 자연어 해석)

#### 주차별 할 일

| 주차 | 할 일 |
|---|---|
| 1주 | 저장소·폴더 구조·Gradle, docker compose, Flyway(V1 스키마·V2 seed), `global/` 공통 코드, Swagger. **주 중반까지 `main` 푸시** |
| 2주 | 회원가입·로그인·JWT 필터·권한 체크, 시연 계정 seed |
| 3주 | 장비 목록·검색(pg_trgm)·상세, 시험유형·시험정보 안내 |
| 4주 | 기관 장비 등록·수정, 유사·중복 장비, 관리자 승인 API |
| 5주 | 대시보드 집계, 자연어 검색 `/search` |
| 6주 | 배포(VM + docker compose), 통합 테스트 주도 |

#### 주의할 점

- 1주차가 늦으면 전원이 늦어집니다. 뼈대는 `astra.security.require-login=false`(로그인 없이 호출 가능)로 시작합니다. 2주차에 JWT 인증 필터를 붙이는 PR에서 `true`로 바꾸세요. `SecurityRequireLoginTest`가 이 모드의 401 응답을 검증합니다.
- 권한 체크(기관은 소속 장비만 수정)는 공통 유틸 하나로 만들어 BE-B도 같이 쓰게 하세요.
- API 수는 가장 많지만 대부분 조회와 CRUD라 난이도는 낮은 편입니다.

#### 담당 API (39개)

| 우선 | Method | URL | 권한 | 설명 |
|---|---|---|---|---|
| P0 | POST | `/auth/email-verifications` | 🔓 | 이메일 인증 코드(6자리) 발송 |
| P0 | POST | `/auth/email-verifications/confirm` | 🔓 | 인증 코드 확인 → 가입용 토큰 |
| P0 | POST | `/auth/signup` | 🔓 | 회원가입 (기업 / 기관 담당자, 이메일 인증 필수, 모두 PENDING) |
| P0 | POST | `/auth/login` | 🔓 | 로그인 → access/refresh 토큰 |
| P0 | GET | `/auth/institutions` | 🔓 | 가입용 기관 목록 (id·이름·지역만) |
| P0 | GET | `/users/me` | 로그인 | 내 정보 + 소속 조직 |
| P0 | GET | `/equipment` | 로그인 | 장비 통합 검색/목록 |
| P0 | GET | `/equipment/{id}` | 로그인 | 장비 상세 (사양, 이용조건, 가동률, 갱신일) |
| P0 | GET | `/test-types` | 로그인 | 시험유형 목록 |
| P1 | GET | `/equipment/{id}/similar` | 로그인 | 동일·유사 장비 (같은 시험유형/분류, 이름 유사도) |
| P1 | POST | `/equipment/compare` | 로그인 | 장비 N개 사양 비교표 |
| P1 | GET | `/search` | 로그인 | 시험항목·자연어 통합 검색 |
| P1 | GET | `/test-types/{code}` | 로그인 | 시험 정보 안내: 가능 기관·예상비용·기간·이용조건 |
| P1 | GET | `/institutions` | 로그인 | 기관 목록 |
| P1 | GET | `/institutions/{id}` | 로그인 | 기관 상세 + 보유 장비 수 |
| P1 | GET | `/institutions/me/equipment` | 🏛 | 우리 기관 장비 목록 (검증 상태, 마지막 갱신일, 갱신 필요 여부) |
| P1 | POST | `/equipment` | 🏛 | 장비 등록. `institutionId`는 로그인 사용자 소속으로 서버가 채움 |
| P1 | PATCH | `/equipment/{id}` | 🏛(소유기관) | 기본정보·이용조건·요금·가동률·가용상태 수정 → `infoUpdatedAt` 갱신 |
| P1 | PUT | `/equipment/{id}/specs` | 🏛(소유기관) | 실제 사양(REAL) 일괄 저장. MOCK 사양은 수정 불가 |
| P1 | PUT | `/equipment/{id}/test-types` | 🏛(소유기관) | 가능 시험유형 + 예상 비용·소요기간 (기능 4 데이터) |
| P1 | DELETE | `/equipment/{id}` | 🏛(소유기관) | 비활성화 (`is_active=false`, 기존 의뢰 이력 보존) |
| P1 | GET | `/dashboard/summary` | 로그인 | 역할별 요약 (아래) |
| P1 | GET | `/dashboard/equipment-stats` | 로그인 | 지역/유형별 장비 수, 기관별 보유 현황 |
| P2 | POST | `/auth/refresh` | 🔓 | 토큰 재발급 |
| P2 | POST | `/auth/logout` | 로그인 | refresh 토큰 폐기 |
| P2 | PATCH | `/users/me` | 로그인 | 내 정보 수정 |
| P2 | GET | `/equipment/duplicates` | 로그인 | 지역 내 중복 장비 그룹 목록 |
| P2 | POST | `/equipment/{id}/confirm` | 🏛(소유기관) | "현재 정보가 맞음" 확인만 → `INSTITUTION_VERIFIED`, `infoUpdatedAt=now` |
| P2 | GET | `/equipment/{id}/change-logs` | 🏛(소유기관) | 누가 언제 무엇을 바꿨는지 |
| P2 | POST | `/admin/equipment/import` | 👑 | 초기 데이터(공개자료 수집 JSON) 적재 |
| P0 | GET | `/admin/users` | 👑 | 회원 목록 (승인 대기 확인) |
| P0 | POST | `/admin/users/{id}/approve` | 👑 | 가입 승인 (PENDING→ACTIVE, 가입하며 만든 조직도 승인) |
| P0 | POST | `/admin/users/{id}/reject` | 👑 | 가입 거절 (→REJECTED, 사유) |
| P2 | PATCH | `/admin/users/{id}/status` | 👑 | 정지·해제 |
| P2 | GET | `/admin/organizations` | 👑 | 조직 목록 |
| P2 | POST | `/admin/organizations` | 👑 | 기관 직접 등록 |
| P2 | POST | `/admin/organizations/{id}/verify` | 👑 | 신규 기관 신청 승인 |
| P2 | PATCH | `/admin/organizations/{id}` | 👑 | 기관 정보 수정 |
| P2 | POST | `/admin/equipment/{id}/transfer` | 👑 | 장비 소속 기관 변경 (잘못 수집된 경우) |

### BE-B · 의뢰 워크플로우 — 허영재

기업과 기관이 의뢰를 주고받는 흐름 전체를 맡습니다. 시연의 후반부(제출 → 보완 → 예약 확정)가 이 사람 담당입니다.

- **담당 패키지**: `request/`, `review/`, `reservation/`, `notification/`, `simulation/`
- **담당 테이블**: test_requests, test_request_revisions, request_status_history, supplement_requests, attachments, reservations, notifications, simulation_models, simulation_presets, simulation_runs

#### 다른 담당자와 주고받는 것

- **제공**: `TestRequestService.createDraft()` (BE-C, AI 초안·자동 추진), `NotificationService.notify()` (전원)
- **받음**: `CurrentUser`·권한 체크 유틸 (BE-A), `RequirementService.getSnapshot()` (BE-C, 의뢰서에 조건 반영)

#### 주차별 할 일

| 주차 | 할 일 |
|---|---|
| 1주 | 의뢰 엔티티, `RequestStatus` enum과 상태 전이 검증 로직 (api-spec §11 표를 그대로 코드로) |
| 2주 | 의뢰 생성·조회·작업본 저장·제출, revision 저장 |
| 3주 | 검토 시작·보완 요청·보완 제출·수락/반려, 상태 이력 |
| 4주 | 예약 제안·확정, 알림 생성·조회 |
| 5주 | 가상 시뮬레이션 (계산형 6종 우선, 나머지 모델은 프리셋 표시만) |
| 6주 | 통합 테스트, 시연 데이터(의뢰 진행 단계별 샘플) 준비 |

#### 주의할 점

- 핵심은 상태 전이입니다. 모든 상태 변경이 한 메서드(`changeStatus`)를 거치게 하고, 그 안에서 전이 가능 여부 검증 → 상태 갱신 → 이력 저장 → 알림을 한 트랜잭션으로 처리하세요.
- 보완 제출 때 새 revision을 만들고 `current_revision_no`를 올리는 부분이 PPT 12p "제출본 이력 분리"의 시연 포인트입니다.
- 시뮬레이션은 장비 데이터 README 정책대로 결과마다 DEMO 경고 문구를 반드시 넣으세요.
- **중복 예약 방지는 필수입니다.** 두 겹으로 막습니다.
- 1차 방어 (서비스 코드): 제안·확정 전에 `existsConfirmedOverlap(equipmentId, startAt, endAt)` 조회 (`start_at < :endAt AND end_at > :startAt AND status='CONFIRMED'`) → 겹치면 `RESERVATION_OVERLAP`
- 2차 방어 (DB 제약): 동시에 확정하는 경우는 코드 검사로 못 막으니 `ex_resv_no_overlap`이 막습니다. `saveAndFlush()` 후 `DataIntegrityViolationException`을 잡아서 SQLSTATE `23P01`이면 `RESERVATION_OVERLAP`, `23505`이면 `RESERVATION_ALREADY_CONFIRMED`로 바꿔 409를 반환하세요.
- 보완 요청 생성도 같은 방식입니다. OPEN 요청이 이미 있으면 `SUPPLEMENT_ALREADY_OPEN` (DB 유니크 인덱스가 최종 방어).

#### 담당 API (33개)

| 우선 | Method | URL | 권한 | 설명 |
|---|---|---|---|---|
| P0 | GET | `/test-requests` | 🏢 🏛 | 목록 — 기업: 내 의뢰 / 기관: 우리 기관 접수함. |
| P0 | GET | `/test-requests/{id}` | 🏢 🏛 | 상세 (현재 상태, 최신 revision, 열린 보완요청, 예약) |
| P0 | PUT | `/test-requests/{id}/draft` | 🏢 | 작업본 revision 내용 저장 (DRAFT 또는 SUPPLEMENT_REQUESTED) |
| P0 | POST | `/test-requests/{id}/submit` | 🏢 | 제출 (DRAFT→SUBMITTED) |
| P0 | POST | `/test-requests/{id}/review/start` | 🏛 | 검토 시작 (SUBMITTED/RESUBMITTED→UNDER_REVIEW), 담당자 지정 |
| P0 | POST | `/test-requests/{id}/supplements` | 🏛 | 보완 요청 (→SUPPLEMENT_REQUESTED) |
| P0 | GET | `/test-requests/{id}/supplements` | 🏢 🏛 | 보완 요청 목록 |
| P0 | POST | `/supplements/{id}/respond` | 🏢 | 보완 제출 → 새 revision 생성, (→RESUBMITTED) |
| P0 | POST | `/test-requests/{id}/accept` | 🏛 | 기술 검토 통과 (→SCHEDULING) |
| P0 | POST | `/test-requests/{id}/reservations` | 🏢 🏛 | 일정 제안 (SCHEDULING 상태에서) |
| P0 | POST | `/reservations/{id}/confirm` | 🏛 | 예약 확정 → 의뢰 CONFIRMED |
| P1 | POST | `/test-requests` | 🏢 | 의뢰 생성(DRAFT) |
| P1 | POST | `/test-requests/{id}/cancel` | 🏢 | 취소 |
| P1 | GET | `/test-requests/{id}/revisions` | 🏢 🏛 | 제출본 이력 목록 |
| P1 | GET | `/test-requests/{id}/history` | 🏢 🏛 | 상태 변경 이력 |
| P1 | POST | `/test-requests/{id}/reject` | 🏛 | 반려 (→REJECTED) |
| P1 | POST | `/test-requests/{id}/complete` | 🏛 | 시험 완료 처리 (CONFIRMED→COMPLETED) |
| P1 | POST | `/reservations/{id}/cancel` | 🏢 🏛 | 예약 취소 |
| P1 | GET | `/equipment/{id}/reservations` | 로그인 | 장비 예약 현황(바쁜 구간만) |
| P1 | GET | `/simulation/models` | 로그인 | 모델 카탈로그 (설명·필수 입력·한계) |
| P1 | GET | `/equipment/{id}/simulation-presets` | 로그인 | 장비의 프리셋 |
| P1 | POST | `/simulation/runs` | 로그인 | 실행 |
| P1 | GET | `/simulation/runs/{id}` | 로그인 | 결과 |
| P1 | GET | `/notifications` | 로그인 | 알림 목록 |
| P1 | GET | `/notifications/unread-count` | 로그인 | 헤더 배지용 |
| P1 | PATCH | `/notifications/{id}/read` | 로그인 | 읽음 |
| P1 | POST | `/notifications/read-all` | 로그인 | 모두 읽음 |
| P2 | GET | `/test-requests/{id}/revisions/{no}` | 🏢 🏛 | 특정 제출본 ( 이면 변경 필드 diff 포함) |
| P2 | POST | `/test-requests/{id}/attachments` | 🏢 🏛 | 첨부 업로드 (multipart) |
| P2 | GET | `/test-requests/{id}/attachments` | 🏢 🏛 | 첨부 목록 |
| P2 | DELETE | `/attachments/{id}` | 업로더 | 첨부 삭제 |
| P2 | POST | `/reservations/{id}/counter` | 🏢 🏛 | 대안 일정 제안 (→COUNTER_PROPOSED) |
| P2 | GET | `/simulation/runs` | 로그인 | 내 실행 이력 |

### BE-C · AI — 주현욱

AI 상담부터 장비 추천, 의뢰서 초안까지 맡습니다. API 수는 가장 적지만 결과가 가장 불확실한 영역이라 가장 먼저 시작합니다.

- **담당 패키지**: `chat/`, `requirement/`, `matching/`, `aipipeline/`, `llm/`
- **담당 테이블**: chat_sessions, chat_messages, test_requirements, requirement_conditions, test_condition_rules, match_runs, match_results, match_condition_checks, ai_pipeline_runs

#### 다른 담당자와 주고받는 것

- **제공**: `RequirementService.getSnapshot()` (BE-B), 문장 → 조건 추출기 (BE-A `/search`에서 재사용)
- **받음**: `EquipmentQueryService` (BE-A, 후보 장비·사양), `TestRequestService.createDraft()` (BE-B, 의뢰서 초안 저장)

#### 주차별 할 일

| 주차 | 할 일 |
|---|---|
| 1주 | Claude 연동 테스트: 문장 → 조건 JSON 추출 (structured outputs). 시연용 문장 10개로 정확도 확인 |
| 2주 | 상담 세션·메시지 API (SSE 스트리밍), 조건 저장·수정·분석 API |
| 3주 | 매칭 엔진: 사양 범위 비교 규칙 → MET/NOT_MET/UNKNOWN, 점수·순위, 추천 이유 문장 생성 |
| 4주 | AI 의뢰서 초안, 자동 추진 (단계별 상태 저장) |
| 5주 | 프롬프트 다듬기, 예외 입력 테스트 (PPT 13p 과제) |
| 6주 | 통합 테스트, 시연 대화 시나리오 고정 |

#### 주의할 점

- 매칭 판단은 LLM에 맡기지 말고 코드(규칙)로 하세요. LLM은 조건 추출과 설명 문장에만 씁니다. 그래야 결과가 매번 같고 근거를 설명할 수 있습니다.
- 매칭 기준은 `test_condition_rules`입니다. 조건 키(`sample_mass`)를 어떤 사양 키(`max_payload`)와 어떤 방식(`MAX_LIMIT`)으로 비교할지가 들어 있고, seed에 15개 시험유형 초안(32개 규칙)이 있습니다. 1주차에 검토해서 확정하세요. 규칙 수정은 `tools/json_to_seed.py`의 `CONDITION_RULES`에서 합니다.
- 장비 데이터의 `frequency`는 RF 장비는 GHz, 진동시험기는 Hz입니다. 비교 전에 단위 환산을 꼭 하세요.
- 시연 중 응답이 느리거나 실패할 수 있으니, 시연 대화는 미리 돌려 결과를 확인해 두세요.

#### 담당 API (20개)

| 우선 | Method | URL | 권한 | 설명 |
|---|---|---|---|---|
| P0 | POST | `/chat/sessions` | 🏢 | 상담 시작 |
| P0 | GET | `/chat/sessions` | 🏢 | 내 상담 목록 |
| P0 | GET | `/chat/sessions/{id}/messages` | 🏢 | 메시지 목록 |
| P0 | POST | `/chat/sessions/{id}/messages` | 🏢 | 메시지 전송 → AI 응답 (`Accept: text/event-stream` 시 SSE 스트리밍) |
| P0 | POST | `/chat/sessions/{id}/requirement` | 🏢 | 대화 내용으로 시험조건(requirement) 생성/갱신 |
| P0 | GET | `/requirements/{id}` | 🏢 | 시험조건 조회 (조건 항목 + 상태) |
| P0 | PUT | `/requirements/{id}/conditions` | 🏢 | 조건 항목 일괄 저장 (사용자 직접 수정) |
| P0 | POST | `/requirements/{id}/analyze` | 🏢 | AI 조건 분석: 누락·미확인 항목 + 질문 제안 |
| P0 | POST | `/requirements/{id}/match-runs` | 🏢 | 매칭 실행 |
| P0 | GET | `/match-runs/{id}` | 🏢 | 추천 결과 + 장비별 조건 비교표 |
| P0 | POST | `/test-requests/ai-draft` | 🏢 | AI 의뢰서 초안 생성 → DRAFT + revision(isAiGenerated) |
| P1 | POST | `/requirements` | 🏢 | 시험조건 직접 생성 (폼 입력) |
| P1 | PATCH | `/requirements/{id}` | 🏢 | 목적·시료명 등 수정 |
| P1 | POST | `/requirements/{id}/confirm` | 🏢 | 조건 확정 (DRAFT→CONFIRMED) |
| P2 | GET | `/requirements/{id}/match-runs` | 🏢 | 이 조건의 매칭 이력 |
| P2 | POST | `/ai/auto-runs` | 🏢 | 시작 또는 → 202 |
| P2 | GET | `/ai/auto-runs/{id}` | 🏢 | 진행 상태 (폴링 1~2초) |
| P2 | GET | `/ai/auto-runs/{id}/events` | 🏢 | (선택) SSE 진행 이벤트 |
| P2 | POST | `/ai/auto-runs/{id}/resume` | 🏢 | WAITING_USER 상태에서 조건 보완 후 재개 |
| P2 | POST | `/ai/auto-runs/{id}/cancel` | 🏢 | 중단 |

---

## 6. 착수 체크리스트 (첫 회의)
- [x] 백엔드 역할 배정: BE-A 윤준수 · BE-B 허영재 · BE-C 주현욱
- [x] GitHub 백엔드 저장소 생성 (`capstoneastra/Astra_Back`)
- [ ] 프론트엔드 저장소 생성, 브랜치 보호 설정, Projects 보드
- [ ] 팀 채팅 방: `#일반`, `#api`, `#스탠드업`
- [ ] Anthropic API 키 발급, 사용 한도 설정, 공유
- [ ] 조건 규칙(`test_condition_rules`) 초안 검토·확정 (예: 진동시험 = `sample_mass` ≤ `max_payload`, `fixture_mass` ≤ `max_fixture_mass`, `frequency` 범위 포함) — 매칭의 기준
- [x] 시연 계정 정하기: 기업 1, 기관 담당자 2, 관리자 1, 승인 대기 1 (V3 seed, `README` 시연 계정 표)
- [ ] 시연 시나리오 1개 확정 (예: 위성 전장품 15kg, 20~2000Hz 진동시험)
