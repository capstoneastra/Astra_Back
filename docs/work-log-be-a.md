# BE-A 작업 기록 (윤준수)

플랫폼 기반(인증·권한, 장비 데이터, 관리자, 대시보드) 작업을 덩어리별로 기록합니다. 최신 작업이 위에 옵니다.
API 형식은 [`api-spec.md`](api-spec.md), 담당·일정은 [`team-guide.md`](team-guide.md) §5 를 봅니다.

| 덩어리 | 내용 | 상태 | main 반영 |
|---|---|---|---|
| 1 | 인증·권한 (회원가입·로그인·JWT·AccessGuard·시연 계정) | ✅ | PR #5 (2026-11-07) |
| 1-2 | 가입 이메일 인증 + 기업·기관 모두 관리자 승인 | ✅ | PR #5 |
| 2 | 장비 목록·검색·상세, 시험유형, 기관, EquipmentQueryService | ✅ | PR #5 |
| 3 | 기관 장비 관리, 유사·중복·비교, 관리자 정지·조직 관리·장비 이관 | ✅ | 아직 (`feature/be-a`) |
| 4 | 대시보드, 자연어 검색 | ⏳ | |
| 6주차 | 배포, 통합 테스트, 시연 시나리오 확인 | ⏳ | |

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
- 정지한 계정의 access 토큰은 만료(최대 1시간)까지 유효 (요청마다 DB 확인은 안 함)
- `POST /admin/equipment/import`(P2) 미구현 (seed 로 적재돼 있음), 90일 경과 알림은 4번 덩어리로
- 로컬 DB 에 확인용 비활성 장비 `ASTRA-EQ-0072` 가 남아 있음 (`docker compose down -v` 로 초기화 가능)

**커밋**: `beb1b22` `d5b3caf` `b341891` `3208007`

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
