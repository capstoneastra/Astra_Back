# 데이터 사전 — V6

전체 구조·제약조건은 [schema.sql](schema.sql), 요청/응답 이름은 [api-spec.md](../api-spec.md)가 기준이다. DB는 snake_case, JSON 필드명은 camelCase이며 조건·사양 키 값은 snake_case를 유지한다. 기능 용어는 [GLOSSARY.md](../../GLOSSARY.md)를 따른다.

## 변경 책임

| 테이블 | 의미 | 값을 변경하는 주체 |
|---|---|---|
| organizations, users, refresh_tokens, email_verifications | 조직·사용자·인증 | BE-A 서비스. 보안시설 안내는 소유기관 담당자/관리자 |
| equipment, equipment_specs, equipment_test_types, equipment_change_logs | 장비 목록·사양·시험 지원·이력 | 소유기관 담당자/관리자, BE-A 서비스가 이력 기록 |
| spec_definitions | 공통 사양 키·기준 단위·값 형식 | BE-A/B/C 합의 후 마이그레이션. 기관이 자유 키를 생성할 수 없음 |
| test_types, test_condition_rules, condition_aliases | 시험유형·입력 조건과 사양의 매핑·동의어 | BE-A/B/C 합의 후 마이그레이션 |
| equipment_downtimes | 정비·운휴 시간 | 소유기관 담당자/관리자. BE-B 예약과 같은 장비 잠금 사용 |
| chat_sessions, chat_messages | 상담과 AI 근거 | 소유 사용자 입력, BE-C 서비스 |
| test_requirements, requirement_conditions | 정리된 시험조건 | 소유 사용자·BE-C 검증 서비스 |
| match_runs, match_results, match_condition_checks | 매칭 당시 조건과 판정 근거 | BE-C 코드. AI는 설명만 생성 |
| ai_pipeline_runs | AI 작업 진행·복구 상태 | BE-C 서비스 |
| test_requests, test_request_revisions, request_status_history | 의뢰 작업본·제출본·전이 이력 | 권한 있는 기업/기관의 BE-B 상태 전이 서비스 |
| supplement_requests, attachments | 보완 요청·자료 | 권한 있는 당사자, BE-B 서비스 |
| reservations | 일정 제안·확정 | 기업/기관 제안, 기관 확정. BE-B 서비스 |
| notifications | 사용자 알림 | 각 도메인 이벤트를 BE-B NotificationService가 저장 |
| simulation_models, simulation_presets, simulation_runs | 시연 모델·설정·실행 기록 | BE-B. 초기 모델·설정은 seed, 실제 장비 인증 근거 아님 |

## 사양과 조건

| 컬럼 | 의미 |
|---|---|
| spec_definitions.spec_key | 장비 사양 식별 키. 예: max_payload |
| name_ko / base_unit / value_type | 한글 표시명 / 저장·비교 기준 단위 / NUMBER·RANGE·TEXT |
| equipment_specs.value_num | NUMBER의 단일 값 |
| min_num / max_num | RANGE의 양끝 값. 둘 다 필수, min ≤ max |
| value_text | TEXT의 공백이 아닌 값 |
| unit / original_unit | 정규화한 저장 단위 / 기관 입력 또는 수집 원문의 단위. original_unit은 비교에 쓰지 않음 |
| source_type / usage_scope | REAL은 실제 근거의 사양, MOCK은 시연값. MOCK의 DEMO_ONLY를 유지 |
| test_condition_rules.condition_key | 사용자가 요구하는 조건. sample_mass와 장비의 max_payload는 서로 다른 키 |
| spec_key / comparator | 비교할 사양 키 / 수치 판정 방식. 사양 키는 사전에 FK로 연결 |
| is_required | 값이 없을 때 MISSING으로 질문해야 하는 조건 |
| condition_aliases.(test_type_code,condition_key,alias) | 시험유형 안의 표현→조건 키. 동일 단위만으로 시료와 고정구를 구분하지 않음 |

세 가지 값 형식 중 정확히 하나를 사용한다. DB CHECK와 사전 형식 검증 트리거가 잘못된 직접 SQL 입력도 거절한다. 숫자 사양은 기준 단위로 저장하며 현재 혼합 단위인 frequency는 GHz를 10⁹배 하여 Hz로 옮긴다. 기존 매칭/시뮬레이션 스냅샷은 당시 근거이므로 소급 변경하지 않는다.

| comparator | 판정 |
|---|---|
| RANGE_WITHIN | 요구 구간(또는 단일 지점)이 장비 범위 안에 들어감 |
| MAX_LIMIT | 요구 최대값 ≤ 장비 단일값 |
| MIN_LIMIT | 장비 단일값 ≤ 요구 최소값. 분해능에 사용 |
| NONE | 일반 텍스트는 기관 확인. accredited_required만 아래 공인 능력 판정으로 처리 |

CONFIRMED는 사용자가 명시·확인한 조건, UNCONFIRMED는 추정·불확실, MISSING은 값 없음이다. 모르는 AI 키·시험유형·단위·위조 근거는 기존 AI_UPSTREAM_ERROR로 거부하며 잘못된 결과를 성공으로 저장하지 않는다.

## 가용 상태와 일정

| 상태 | 기관 판단 기준 | 검색·매칭·기간 조회 |
|---|---|---|
| AVAILABLE | 기관이 운영 가능하다고 확인 | 예약·정비가 없는 구간만 가능. 확인이 오래되면 UNKNOWN |
| BUSY | 사용 중 또는 일정 협의 필요 | 매칭 후보는 유지. 종료 근거가 없으므로 기간 조회에서는 보수적으로 BUSY |
| MAINTENANCE | 정비 중 | 매칭 제외. 기간 조회는 불가 |
| UNAVAILABLE | 이용 중단·제공 불가 | 매칭 제외. 기간 조회는 불가 |
| UNKNOWN | 상태를 확인하지 못함 | 기관 확인 필요. 빈 달력으로 AVAILABLE을 만들지 않음 |

| 컬럼 | 의미·변경 기준 |
|---|---|
| availability_updated_at / availability_updated_by | 기관이 상태를 입력한 시각/사용자. 일반 정보 수정이나 confirm으로 덮어쓰지 않음 |
| availability_note | 상태 판단 근거. DEMO 상태는 시연용임을 명시 |
| availability_reminded_at | 해당 상태 확인 주기에 알림을 보낸 시각. 상태 재확인 때 초기화 |
| lead_time_days | 시험 착수까지 기관이 안내한 대기 일수. 0 이상, NULL은 미확인 |
| equipment_downtimes.equipment_id | 대상 장비 |
| start_at / end_at | 시간대 포함 `[시작, 종료)`. 종료 > 시작 |
| reason / created_by / created_at | 정비·운휴 사유 / 등록 사용자 / 등록 시각 |
| reservations.status | PROPOSED·COUNTER_PROPOSED는 제안, CONFIRMED만 점유, CANCELED는 취소 |

상태 확인일이 없거나 90일 초과, UNKNOWN, 기관 확인자가 없는 DEMO는 확인 필요다. 알림은 상태 확인일(없으면 생성일)로 90일을 계산하고 매일 09:00 Asia/Seoul에 보낸다. 중단 중 누락분은 보충하며, 상태 확인 주기당 한 번 보낸다. 담당자가 없으면 발송 완료로 기록하지 않는다.

기간 조회의 날짜는 한국 날짜이며 `to` 날짜를 포함한다. 확정 예약과 정비를 합친 구간 우선순위는 UNAVAILABLE > MAINTENANCE > BUSY > UNKNOWN > AVAILABLE이다. 같은 장비의 예약 확정과 정비 등록은 행 잠금·DB 트리거로 경합을 막는다. 예약자·의뢰 정보는 가용성 응답에 넣지 않는다.

## 현업 정보

| 컬럼/값 | 의미 |
|---|---|
| equipment_test_types.is_accredited | 해당 장비+시험유형의 공인성적서 가능 여부. TRUE/FALSE/NULL(미확인) |
| accreditation_no / accreditation_valid_until | 인정번호/유효 마지막 날짜. TRUE에는 둘 다 필수. 한국 날짜로 만료 검사 |
| operation_mode.INSTITUTION_OPERATED | 기관 전담 운용 |
| operation_mode.SUPERVISED_SELF_SERVICE | 기관 입회 후 자가 사용 |
| operation_mode.SELF_SERVICE | 자가 사용 |
| operation_mode.UNKNOWN | 운용 방식 미확인 |
| organizations.has_secure_facility | 보안시설 있음/없음/미확인 |
| organizations.security_note | 시료·데이터 반출 정책, NDA 등 기관 협의 안내. 법적 허가 판정 아님 |
| est_cost_min / est_cost_max / est_duration_days | 시험유형별 예상 비용 범위(원)/시험 소요일. lead_time_days와 구분 |

`accredited_required`는 선택 TEXT 조건(`필요`/`불필요`), spec_key=NULL, comparator=NONE이다. `필요`이고 기관 공인정보가 유효하면 MET, 불가/만료면 NOT_MET, 미확인이면 UNKNOWN이다. 새 숫자 comparator나 가짜 수치 사양으로 저장하지 않는다.

의뢰서 `content.serviceRequirements`는 `accreditedRequired`, `operatorSupportRequested`, `securityOrExportControlled`의 boolean 또는 null이다. 생략/null은 미확인이고 false와 다르다. 숫자 조건은 기존 conditions에 유지한다.

## 시험유형 연결 범위

사용자 결정에 따라 활성 TEST/MEASUREMENT 장비의 연결 누락을 테스트하고, 등록·시험유형 교체·장비 분류 변경 API에서도 연결을 필수로 검증한다. 가공 20대·소프트웨어 4대·지원 1대에는 이름만 보고 시험유형을 만들지 않는다. 기존 71대는 검색·비교에 모두 유지하고 매칭은 실제 equipment_test_types 연결이 있는 장비만 사용한다.
