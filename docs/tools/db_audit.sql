-- =====================================================================
-- DB 빈 값·연결 점검 (조회만 함, 아무것도 바꾸지 않음)
-- 실행 예 (시연 서버):  docker compose -f compose.prod.yaml exec -T db psql -U astra -d astra < docs/tools/db_audit.sql
-- 결과의 count 가 0 이 아닌 줄만 보면 됩니다. 0 이 정상인 항목과 참고용 항목은 "기대" 칸에 적어 둠
-- =====================================================================
SELECT '점검 항목' AS item, '개수' AS count, '기대' AS expected
UNION ALL SELECT * FROM (VALUES
-- 장비 ---------------------------------------------------------------
 ('활성 장비 수',
  (SELECT count(*) FROM equipment WHERE is_active)::text, '참고 (V22 후 335)'),
 ('활성 장비 중 시험유형 없음',
  (SELECT count(*) FROM equipment e WHERE is_active AND NOT EXISTS
     (SELECT 1 FROM equipment_test_types t WHERE t.equipment_id = e.id))::text, '0 (V22 후)'),
 ('활성 장비 중 지역 없음',
  (SELECT count(*) FROM equipment WHERE is_active AND coalesce(trim(region),'') = '')::text, '0'),
 ('활성 장비 중 이용료 문구 없음',
  (SELECT count(*) FROM equipment WHERE is_active AND coalesce(trim(fee_text),'') = '')::text, '0 (금액 없으면 ''협의'')'),
 ('활성 장비 중 가동률 없음',
  (SELECT count(*) FROM equipment WHERE is_active AND utilization_rate IS NULL)::text, '0'),
 ('활성 장비 중 분류(category) 없음',
  (SELECT count(*) FROM equipment WHERE is_active AND coalesce(trim(category),'') = '')::text, '참고'),
 ('활성 장비 중 제조사 또는 모델 없음',
  (SELECT count(*) FROM equipment WHERE is_active AND (coalesce(trim(manufacturer),'') = '' OR coalesce(trim(model),'') = ''))::text, '참고'),
 ('활성 장비 중 사양 하나도 없음',
  (SELECT count(*) FROM equipment e WHERE is_active AND NOT EXISTS
     (SELECT 1 FROM equipment_specs s WHERE s.equipment_id = e.id))::text, '참고'),
 ('활성 장비 중 예시(MOCK) 사양만 있음',
  (SELECT count(*) FROM equipment e WHERE is_active AND EXISTS (SELECT 1 FROM equipment_specs s WHERE s.equipment_id = e.id)
     AND NOT EXISTS (SELECT 1 FROM equipment_specs s WHERE s.equipment_id = e.id AND s.source_type <> 'MOCK'))::text, '참고'),
 ('필수 조건 사양이 없는 장비-시험유형 연결',
  (SELECT count(*) FROM equipment_test_types t JOIN equipment e ON e.id = t.equipment_id AND e.is_active
     JOIN test_condition_rules r ON r.test_type_code = t.test_type_code AND r.is_required AND r.spec_key IS NOT NULL
     WHERE NOT EXISTS (SELECT 1 FROM equipment_specs s WHERE s.equipment_id = t.equipment_id AND s.spec_key = r.spec_key))::text, '참고 (매칭에서 확인 필요로 나옴)'),
-- 시험유형 -------------------------------------------------------------
 ('장비가 하나도 없는 시험유형',
  (SELECT count(*) FROM test_types tt WHERE NOT EXISTS (SELECT 1 FROM equipment_test_types t JOIN equipment e ON e.id = t.equipment_id
     WHERE t.test_type_code = tt.code AND e.is_active))::text, '0'),
 ('필수 조건이 없는 시험유형',
  (SELECT count(*) FROM test_types tt WHERE NOT EXISTS (SELECT 1 FROM test_condition_rules r WHERE r.test_type_code = tt.code AND r.is_required))::text, '0'),
-- 조직·회원 ------------------------------------------------------------
 ('승인된 조직 중 지역 없음',
  (SELECT count(*) FROM organizations WHERE is_verified AND coalesce(trim(region),'') = '')::text, '0'),
 ('승인된 조직 중 전화 없음',
  (SELECT count(*) FROM organizations WHERE is_verified AND coalesce(trim(phone),'') = '')::text, '참고'),
 ('활성 회원 중 이름 또는 전화 없음',
  (SELECT count(*) FROM users WHERE status = 'ACTIVE' AND (coalesce(trim(name),'') = '' OR coalesce(trim(phone),'') = ''))::text, '0'),
 ('기관·대학 회원 중 연구자번호 없음',
  (SELECT count(*) FROM users u JOIN organizations o ON o.id = u.organization_id
     WHERE u.status = 'ACTIVE' AND o.org_type IN ('INSTITUTION','UNIVERSITY') AND coalesce(trim(u.researcher_no),'') = '')::text, '참고 (시연 계정)'),
-- 의뢰 -----------------------------------------------------------------
 ('제출된 의뢰 중 제목 없음',
  (SELECT count(*) FROM test_requests WHERE status <> 'DRAFT' AND coalesce(trim(title),'') = '')::text, '0'),
 ('제출된 의뢰 중 이용 희망 기간 없음',
  (SELECT count(*) FROM test_requests WHERE status <> 'DRAFT' AND (desired_start_date IS NULL OR desired_end_date IS NULL))::text, '참고 (필수 검사 전 데이터)'),
 ('제출된 의뢰 중 장비가 비활성',
  (SELECT count(*) FROM test_requests r JOIN equipment e ON e.id = r.equipment_id WHERE r.status <> 'DRAFT' AND NOT e.is_active)::text, '참고'),
 ('완료된 의뢰 중 결과 없음',
  (SELECT count(*) FROM test_requests r WHERE r.status = 'COMPLETED' AND NOT EXISTS (SELECT 1 FROM test_results t WHERE t.request_id = r.id))::text, '0'),
 ('임시저장(DRAFT) 의뢰',
  (SELECT count(*) FROM test_requests WHERE status = 'DRAFT')::text, '참고'),
-- 조건·매칭 ------------------------------------------------------------
 ('시험조건 중 확정(CONFIRMED)',
  (SELECT count(*) FROM requirement_conditions WHERE status = 'CONFIRMED')::text, '참고'),
 ('시험조건 중 미확정(UNCONFIRMED)',
  (SELECT count(*) FROM requirement_conditions WHERE status = 'UNCONFIRMED')::text, '참고 (매칭에서 확인 필요로 나옴)'),
 ('시험조건 중 값 없음(MISSING)',
  (SELECT count(*) FROM requirement_conditions WHERE status = 'MISSING')::text, '참고 (매칭에서 확인 필요로 나옴)'),
 ('매칭 판정 중 확인 필요(UNKNOWN)',
  (SELECT count(*) FROM match_condition_checks WHERE result = 'UNKNOWN')::text, '참고'),
 ('매칭 판정 중 한글 이름 없음',
  (SELECT count(*) FROM match_condition_checks WHERE coalesce(trim(label),'') = '')::text, '0 (V21 후)')
) AS v(item, count, expected);

-- 매칭에서 확인 필요가 된 조건을 이유별로 (최근 매칭 기준)
SELECT c.condition_key, c.label, c.note, count(*) AS n
FROM match_condition_checks c
WHERE c.result = 'UNKNOWN'
GROUP BY 1, 2, 3 ORDER BY n DESC LIMIT 20;
