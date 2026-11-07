# ASTRA Backend

우주항공 시험장비 공동활용 플랫폼 **ASTRA**의 백엔드입니다. (AI 디자인 캡스톤 7조)

| 항목 | 버전 |
|---|---|
| Java | 21 (LTS) |
| Spring Boot | 4.1.2 |
| Gradle | 9.8.2 (Wrapper) |
| DB | PostgreSQL 16 + Flyway |

## 실행하기

**준비물**: JDK 21, Docker Desktop (켜져 있어야 함)

```bash
git clone https://github.com/capstoneastra/Astra_Back.git
cd Astra_Back
cp .env.example .env        # 필요한 값 채우기 (DB 는 기본값 그대로 두면 됨)
./gradlew bootRun           # Windows: gradlew.bat bootRun
```

- `bootRun`을 하면 `compose.yaml`의 PostgreSQL 16이 자동으로 뜨고, Flyway가 `V1__init.sql`(테이블)과 `V2__seed.sql`(장비 71대 등 초기 데이터)을 적용합니다.
- Swagger UI: http://localhost:8080/swagger-ui.html
- 헬스체크: http://localhost:8080/actuator/health
- IntelliJ: **Project SDK와 Gradle JVM을 모두 21**로 맞춘 뒤 `AstraApplication`을 실행하면 됩니다.
- DB를 처음부터 다시 만들려면: `docker compose down -v` 후 다시 실행

## 테스트

```bash
./gradlew test
```

Testcontainers가 테스트 전용 PostgreSQL 16을 띄워서 실제 DB로 검증합니다 (Docker 필요).

| 테스트 | 확인하는 것 |
|---|---|
| `DatabaseSchemaTest` | 마이그레이션 적용, 초기 데이터 개수, 조건 규칙의 사양 키 존재, **중복 예약 방지**, 의뢰당 확정 예약 1개, 의뢰당 답변 대기 보완 요청 1개 |
| `ApiConventionTest` | 공통 실패 응답 형식, Swagger·헬스체크 접근 |
| `SecurityRequireLoginTest` | 로그인 필수 모드에서 비로그인 호출 시 `401 AUTH_REQUIRED` |

## 구조

```
src/main/java/kr/astra/
├── global/         공통: 응답 형식, 에러 코드, 예외 처리, 보안·Swagger 설정, BaseTimeEntity   ← BE-A (모두 사용)
├── auth/ user/ organization/ admin/ equipment/ testtype/ dashboard/                   ← BE-A
├── request/ review/ reservation/ notification/ simulation/                           ← BE-B
└── chat/ requirement/ matching/ aipipeline/ llm/                                     ← BE-C
```

- 각 도메인은 `controller / service / repository / entity / dto`로 나뉩니다. 각 패키지의 `package-info.java`에 담당자와 역할이 적혀 있습니다.
- `*/entity/`에는 DB의 CHECK 제약과 값이 1:1로 맞는 **enum**이 미리 들어 있습니다(29개). 새로 만들지 말고 이걸 쓰세요.
- 담당자끼리 주고받는 **내부 인터페이스**는 이름과 입출력만 정의되어 있습니다. 구현은 각 담당자가 합니다.

| 인터페이스 | 구현 | 사용 |
|---|---|---|
| `equipment.service.EquipmentQueryService` | BE-A | BE-C (매칭) |
| `requirement.service.RequirementService` | BE-C | BE-B (의뢰서) |
| `request.service.TestRequestService` | BE-B | BE-C (AI 초안·자동 추진) |
| `notification.service.NotificationService` | BE-B | 전원 |
| `global.security.CurrentUser` | BE-A (JWT 필터에서 생성) | 전원 |

## 개발 규칙 (요약)

자세한 내용은 [`docs/team-guide.md`](docs/team-guide.md)를 보세요.

- **API 경로**: 컨트롤러에는 [`docs/api-spec.md`](docs/api-spec.md)의 경로를 그대로 씁니다. `/api/v1`은 자동으로 붙습니다.
- **응답**: `ApiResponse.ok(data)`로 감싸서 반환합니다. 실패는 `throw new BusinessException(ErrorCode.XXX)`로 던집니다. 에러 코드를 추가하면 api-spec.md §0-3도 같이 수정합니다.
- **DB 변경**: 이미 병합된 마이그레이션 파일은 절대 수정하지 않습니다. 새 파일 `V3__설명.sql`을 추가하고, 번호는 채팅으로 먼저 선점합니다. `docs/design/schema.sql`도 같은 PR에서 함께 수정합니다.
- **Entity**: `ddl-auto=validate`라서 Entity와 테이블이 다르면 서버가 뜨지 않습니다. 컬럼은 `docs/design/schema.sql`과 정확히 맞추세요.
- **제약 위반 처리**: 예약 겹침 같은 DB 제약 위반은 `saveAndFlush()` 후 `DbConstraints.violates(e, ...)`로 구분해서 409로 바꿉니다.
- **로그인**: 지금은 `astra.security.require-login=false`라서 로그인 없이 모든 API를 호출할 수 있습니다. JWT 필터를 붙이는 PR(BE-A)에서 `true`로 바꿉니다.
- **브랜치**: `main` 하나만 씁니다. `main`에서 `feature/#이슈번호-작업내용`으로 따서 작업하고 `main`으로 PR을 올립니다. GitHub Actions 빌드 통과 + 리뷰 1명 승인 후 Squash merge합니다. 발표·중간점검 버전은 태그(`v0.1-midterm` 등)로 표시합니다.
- **비밀값**: `.env`, API 키, 비밀번호는 절대 커밋하지 않습니다.

## 문서

| 문서 | 내용 |
|---|---|
| [`docs/api-spec.md`](docs/api-spec.md) | API 명세 (90개), 에러 코드, 매칭 규칙, 의뢰 상태 전이표 |
| [`docs/team-guide.md`](docs/team-guide.md) | 기술 스택, 협업 규칙, 담당자별 API·주차별 계획 |
| [`docs/design/schema.sql`](docs/design/schema.sql) | 전체 테이블 설계 (27개) |
| [`docs/design/erd.dbml`](docs/design/erd.dbml) | ERD (dbdiagram.io에 붙여넣기). `python docs/design/tools/schema_to_dbml.py`로 생성 |
| [`docs/design/seed.sql`](docs/design/seed.sql) | 초기 데이터. `docs/design/tools/json_to_seed.py`로 생성 |
