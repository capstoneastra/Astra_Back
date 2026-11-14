# 백엔드 보완 확인 및 인계

확인 기준: 로컬 `main`의 `d50fe2d`, Flyway V1~V5와 기준 문서 및 현재 Java 코드. 배포 DB를 조회한 수치는 아니다. `docs/tME.md`는 저장소에 없어서 역할·협업 기준은 `docs/team-guide.md`로 확인했다.

## 변경 전 확인

| 항목 | 직접 확인한 결과 | 판단 |
|---|---|---|
| 기관·장비 | 기관 9개, 활성 장비 71대 | 기존 수치 유지 |
| 가용 상태 | UNKNOWN 71대 | 전용 확인 시각·담당자·근거·정비 일정 필요 |
| 사양 | 117건: MOCK 110건, REAL 7건. REAL은 장비 2대에만 존재 | 실제 사양을 수집한 것으로 바꾸거나 표시하지 않음 |
| 사양 키 | 29개, 조건 키 23개, 규칙 32건. 사전 FK 없음 | DB 사전·값 형식·단위 제약 추가 |
| 단위 | frequency 16건에 GHz/Hz 혼재 | Hz로 저장, original_unit 보관 |
| 값 | seed에서 역전 범위·빈 값 0건 | 기존 API 검증은 있으나 DB 제약 부재 |
| 시험유형 연결 | 48건, 장비 46대. 미연결 25대 = 가공 20·소프트웨어 4·지원 1 | 사용자 결정: 기존 25대는 검색·비교 유지. TEST/MEASUREMENT만 연결 필수 검증 |
| AI 매핑 | DB 규칙·시험유형 enum, 서버 검증, 단위 환산, 후보 제한, 코드 판정, 근거 저장 구현됨 | 재작성 대신 사전·동의어·공인 판정 연결 |
| 갱신 알림 | info_updated_at 기준 90~91일 구간, EQUIPMENT_UPDATE_REQUIRED 구현됨 | 상태 확인일로 분리하고 누락·중복 보완 |
| 비교 | 요금·기간·시험별 예상 비용 응답 구현됨. 요금 숫자 3대, duration_text 0대, 시험별 비용·기간 48건 모두 NULL | 기존 응답에 대기·운용·공인·확인 필요 추가 |
| 현업 정보 | 공인·운용·보안 전용 필드 없음 | 신규 필드와 관리·조회 계약 추가 |

## 확정한 작업 범위

- V6 사용은 사용자가 확인했다. V1~V5는 수정하지 않는다.
- 사용자 지시에 따라 이번 임시 브랜치는 병합 없이 삭제하고 `main` 작업 폴더에서 계속했다. 후속 요청에 따라 DB·장비 운영·AI·문서로 커밋을 나누어 main에 직접 push한다. 배포는 별도 작업이다.
- 기관 9개·장비 71대·사양 117건·시험유형 15개·연결 48건은 유지한다. 사양 사전 29건, 동의어 6건, 선택 공인 요구 규칙 15건을 추가해 규칙은 47건이다.
- 시연 상태는 기존 MOCK 장비 45 AVAILABLE, 46 BUSY, 50 MAINTENANCE, 51 UNAVAILABLE에만 넣는다. 나머지 67대는 UNKNOWN. 기관 담당자가 확인한 사실로 표시하지 않는다.
- 공인 여부·대기 기간·운용 방식·보안 시설은 근거 없이 채우지 않는다. 실제 사양 110건을 만들어 REAL로 승격하지 않는다.
- 기존 AI 초안의 별도 연동 문제, 인증·배포 등 이 과제와 직접 관계없는 부분은 수정하지 않는다.

## 팀 공지 필요

BE-A/B/C와 FE에 아래 계약을 전달해야 한다. 이 작업에서 팀 채팅에 메시지를 발송하지 않았다.

| 협의 대상 | 변경 계약 |
|---|---|
| BE-A ↔ BE-C | spec_definitions, 조건 키와 사양 키 구분, frequency 기준 Hz, originalUnit 표시용, 사전 밖 키·형식 거부 |
| BE-A ↔ BE-B | 확정 예약과 정비는 같은 장비 행 잠금, `[startAt,endAt)` 겹침 기준. RESERVATION_OVERLAP 재사용 |
| BE-A ↔ BE-B | availability_updated_at와 알림 주기. 일반 정보 수정·confirm으로 상태 확인 시각을 갱신하지 않음 |
| BE-A ↔ BE-C | UNAVAILABLE/MAINTENANCE 후보 제외, UNKNOWN·90일 경과·DEMO는 기관 확인 필요 |
| BE-C ↔ BE-B/FE | accredited_required 조건(TEXT: 필요/불필요), content.serviceRequirements의 nullable boolean 세 항목 |
| FE | 신규 가용성·정비·보안·사양 사전 API, accreditedOnly, operationMode, 비교 응답 확장, availabilityNeedsConfirmation |
| 전원 | V6 적용, 규칙 수 32→47, rule-v2, 새 OperationMode enum. 공통 global/의 에러 코드·enum은 추가하지 않음 |

## 평가와 실행

- `ConditionMappingEvaluationTest`는 `ASTRA_RUN_LLM_EVAL=true`일 때만 실제 LLM을 호출한다. PostgreSQL Testcontainers와 현재 설정한 공급자의 API 키가 필요하다. 기본 테스트에서는 비활성화한다.
- 25개 문장은 `src/test/resources/condition-mapping-cases.json`. 수치는 seed 장비 48(진동), 37(RF), 33(온습도), 18(하중), 22(배율), 20(경도), 58~60(전기), 62(X선)에서 가져오거나 같은 값의 단위를 환산했다.
- 지표는 시험유형과 추출된 조건 키 집합의 완전일치율, DB 목록 안의 응답 비율이다. 자동 보충된 MISSING 조건은 추출된 키에서 제외한다. 오류도 분모에 포함하며 결과는 `build/reports/condition-mapping.json`에 기록한다. 기준은 25개 중 23개 이상 완전일치다.
- 실제 LLM 평가를 실행하지 않은 상태에서는 정확도 수치를 주장하지 않는다. 단위 테스트의 모의 응답 통과율은 매핑 정확도가 아니다.
- DB 통합 검증 명령: `./gradlew test --tests kr.astra.DatabaseSchemaTest --tests kr.astra.EquipmentApiTest --tests kr.astra.EquipmentManageTest --tests kr.astra.RequestWorkflowTest` (Docker 필요).

## 이번 실행 결과 (2026-11-14)

- Java 21에서 전체 테스트 소스 컴파일 성공. 장비 서비스·매칭·LLM·의뢰 부가요건·조건 검증의 17개 테스트 클래스, 74개 항목 중 **73개 통과, 실패 0개, 실제 LLM 평가 1개 비활성화**. 요약은 로컬 `build/reports/backend-hardening-unit-summary.json`에 보관한다.
- PostgreSQL 파서로 V1~V6, schema.sql, seed.sql 문법 검증을 통과했다. 마이그레이션을 누적한 구조와 설계 문서의 **31개 테이블·319개 컬럼 이름/자료형**이 일치한다. ERD도 다시 생성했다.
- seed INSERT를 메모리 SQLite로 읽어 기관 9·장비 71·사양 117·시험유형 15·연결 48을 재확인했다. 117개 사양의 수치·출처·표시명 보존과 GHz→Hz 정확 환산, 25개 평가 문장의 정답 키가 DB 규칙에 속하는지 확인했다. 이는 PostgreSQL 제약 실행이나 실제 LLM 정확도 검증을 대신하지 않는다.
- V2의 실제 행을 생성기 입력으로 복원해 새 json_to_seed.py가 만든 사전·사양·조건 규칙·동의어·시험유형 연결이 설계 seed와 일치함을 확인했다. 사양 사전 29·동의어 6·조건 규칙 47이며 시연 상태는 UNKNOWN 67, 나머지 네 상태 각 1대다.
- **DatabaseSchemaTest는 Docker 환경을 찾지 못해 초기화 단계에서 실패했다**(`Could not find a valid Docker environment`). Flyway 실행, Hibernate validate, DB 트리거·동시성, HTTP 통합 검증은 Docker가 있는 환경에서 위 명령으로 실행해야 한다. 현재 결과를 DB 통합 테스트 통과로 보고하지 않는다.
- V1~V5 변경 없음, `git diff --check` 통과. 최초 검증은 main의 미커밋 변경에서 수행했으며, 후속 커밋·push 요청에서도 구현 코드는 동일하다. 임시 기능 브랜치는 병합 없이 삭제했고 기존 feature/be-c 브랜치는 유지했다.
- 사용자 요청에 따라 임시 SQL 파서(`astra-schema-tools`), 실패한 설치의 임시 폴더 4개, 생성기 검증용 복원 데이터와 일회성 검증 스크립트를 삭제하고 경로가 없음을 확인했다. 저장소 소스·문서와 테스트 결과 보고서는 보존했다.

상태·컬럼의 의미와 변경 책임은 [데이터 사전](design/data-dictionary.md), API 계약은 [API 명세](api-spec.md)를 따른다.
