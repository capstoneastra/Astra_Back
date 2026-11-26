-- =====================================================================
-- V22: 시험유형이 없던 장비 50대 정리 (BE-A, 2026-11-27)
--  화면에 "제작·가공 장비"로만 보이고 시험유형 검색·매칭에 안 나오던 장비들.
--  장비 수를 유지하려고 지우지 않고, 맞는 시험유형을 새로 만들어 연결함.
--   · 가공·시제품 제작  : 머시닝센터·가공기·툴프리세터·로봇 (작업영역·작업 반경)
--   · 적층제조          : 금속 3D 프린터·DED 복합기 (조형 크기)
--   · 성형·열처리       : 프레스·오토클레이브·열처리로·HIP (가압력·온도)
--   · 비파괴검사(초음파): 초음파·위상배열 탐상기 (탐촉자 주파수)
--   · 질량특성 측정     : 무게중심·관성모멘트 측정기 (시료 질량)
--   · 시편 마운팅·절단·폴리싱기는 분석 전 시편 준비라 기존 '재료 분석'에 연결
--   · CAD/CAM 소프트웨어 4종은 시험 장비가 아니므로 목록에서만 숨김(is_active=FALSE, 행은 유지)
--  ASTRA-CAND 장비의 사양은 원래부터 MOCK(예시) 값이라 화면에 '예시'로 계속 표시됨.
-- =====================================================================

-- 1) 새 시험유형 -----------------------------------------------------------
INSERT INTO test_types (code, name_ko, description, keywords, display_order) VALUES
    ('MACHINING', '가공·시제품 제작', '머시닝센터·로봇 등으로 시제품·부품을 가공 (시험 전 시편·시제품 준비 포함)',
     ARRAY['가공','머시닝','밀링','선반','드릴','절삭','시제품','시작품','5축','로봇','툴프리세터']::text[], 15),
    ('ADDITIVE_MANUFACTURING', '적층제조(금속 3D 프린팅)', '금속 3D 프린터(PBF·DED)로 시제품·부품 제작',
     ARRAY['3D프린팅','3D 프린팅','3D프린터','3D 프린터','적층','적층제조','PBF','DED','금속 프린팅']::text[], 16),
    ('FORMING_HEAT_TREATMENT', '성형·열처리', '프레스·단조·오토클레이브 성형과 진공 열처리·HIP',
     ARRAY['프레스','단조','성형','오토클레이브','복합재','열처리','HIP','열간등방압','소성가공']::text[], 17),
    ('NDT_ULTRASONIC', '비파괴검사(초음파)', '초음파·위상배열 초음파 탐상으로 내부 결함 검사',
     ARRAY['초음파','탐상','비파괴','UT','PAUT','위상배열','결함']::text[], 18),
    ('MASS_PROPERTIES', '질량특성 측정', '무게중심·관성모멘트·관성곱 측정',
     ARRAY['질량특성','무게중심','관성모멘트','관성곱','CG','MOI','스핀 밸런스']::text[], 19);

-- 2) 조건 규칙 (spec_key 는 모두 spec_definitions 에 있는 키) -------------
INSERT INTO test_condition_rules (test_type_code, condition_key, label, value_type, unit, is_required, spec_key, comparator, display_order) VALUES
    ('MACHINING', 'part_size_x', '가공물 크기 X', 'NUMBER', 'mm', TRUE, 'work_envelope_x', 'MAX_LIMIT', 0),
    ('MACHINING', 'part_size_y', '가공물 크기 Y', 'NUMBER', 'mm', TRUE, 'work_envelope_y', 'MAX_LIMIT', 1),
    ('MACHINING', 'part_mass', '가공물 질량', 'NUMBER', 'kg', FALSE, 'max_payload', 'MAX_LIMIT', 2),
    ('MACHINING', 'reach', '필요 작업 반경(로봇)', 'NUMBER', 'mm', FALSE, 'robot_reach', 'MAX_LIMIT', 3),
    ('MACHINING', 'part_material', '가공 재질', 'TEXT', NULL, FALSE, NULL, 'NONE', 4),
    ('ADDITIVE_MANUFACTURING', 'build_size_x', '조형물 크기 X', 'NUMBER', 'mm', TRUE, 'work_envelope_x', 'MAX_LIMIT', 0),
    ('ADDITIVE_MANUFACTURING', 'build_size_y', '조형물 크기 Y', 'NUMBER', 'mm', TRUE, 'work_envelope_y', 'MAX_LIMIT', 1),
    ('ADDITIVE_MANUFACTURING', 'build_material', '적층 소재(분말)', 'TEXT', NULL, FALSE, NULL, 'NONE', 2),
    ('FORMING_HEAT_TREATMENT', 'process_type', '공정 종류(프레스·단조·성형·열처리)', 'TEXT', NULL, TRUE, NULL, 'NONE', 0),
    ('FORMING_HEAT_TREATMENT', 'press_force', '필요 가압력', 'NUMBER', 'kN', FALSE, 'max_press_force', 'MAX_LIMIT', 1),
    ('FORMING_HEAT_TREATMENT', 'process_temperature', '공정 온도', 'RANGE', '°C', FALSE, 'temperature', 'RANGE_WITHIN', 2),
    ('FORMING_HEAT_TREATMENT', 'sample_size_x', '시료 크기 X(폭)', 'NUMBER', 'mm', FALSE, 'chamber_width', 'MAX_LIMIT', 3),
    ('FORMING_HEAT_TREATMENT', 'sample_size_z', '시료 크기 Z(높이)', 'NUMBER', 'mm', FALSE, 'chamber_height', 'MAX_LIMIT', 4),
    ('NDT_ULTRASONIC', 'frequency', '탐촉자 주파수', 'RANGE', 'Hz', TRUE, 'frequency', 'RANGE_WITHIN', 0),
    ('NDT_ULTRASONIC', 'inspection_target', '검사 대상·결함 종류', 'TEXT', NULL, FALSE, NULL, 'NONE', 1),
    ('MASS_PROPERTIES', 'sample_mass', '시료 질량', 'NUMBER', 'kg', TRUE, 'max_payload', 'MAX_LIMIT', 0),
    ('MASS_PROPERTIES', 'measurement_item', '측정 항목(무게중심·관성모멘트 등)', 'TEXT', NULL, FALSE, NULL, 'NONE', 1);

-- 3) 장비 ↔ 시험유형 연결 (시험유형이 하나도 없는 장비만, external_id 기준이라 DB 마다 같은 결과) ---
INSERT INTO equipment_test_types (equipment_id, test_type_code)
SELECT e.id, m.code
FROM equipment e
JOIN (VALUES
    ('ASTRA-CAND-0001','MACHINING'), ('ASTRA-CAND-0002','MACHINING'), ('ASTRA-CAND-0003','MACHINING'),
    ('ASTRA-CAND-0004','MACHINING'), ('ASTRA-CAND-0005','MACHINING'), ('ASTRA-CAND-0006','MACHINING'),
    ('ASTRA-CAND-0007','MACHINING'), ('ASTRA-CAND-0008','MACHINING'), ('ASTRA-CAND-0009','MACHINING'),
    ('ASTRA-CAND-0010','MACHINING'), ('ASTRA-CAND-0011','MACHINING'), ('ASTRA-CAND-0012','MACHINING'),
    ('ASTRA-DEMO-0250','MACHINING'), ('ASTRA-DEMO-0251','MACHINING'), ('ASTRA-DEMO-0252','MACHINING'),
    ('ASTRA-DEMO-0253','MACHINING'), ('ASTRA-DEMO-0254','MACHINING'), ('ASTRA-DEMO-0255','MACHINING'),
    ('ASTRA-DEMO-0256','MACHINING'), ('ASTRA-DEMO-0266','MACHINING'), ('ASTRA-DEMO-0267','MACHINING'),
    ('ASTRA-DEMO-0268','MACHINING'),
    ('ASTRA-CAND-0064','ADDITIVE_MANUFACTURING'), ('ASTRA-CAND-0065','ADDITIVE_MANUFACTURING'),
    ('ASTRA-CAND-0066','ADDITIVE_MANUFACTURING'), ('ASTRA-CAND-0067','ADDITIVE_MANUFACTURING'),
    ('ASTRA-DEMO-0257','ADDITIVE_MANUFACTURING'), ('ASTRA-DEMO-0258','ADDITIVE_MANUFACTURING'),
    ('ASTRA-DEMO-0259','ADDITIVE_MANUFACTURING'), ('ASTRA-DEMO-0260','ADDITIVE_MANUFACTURING'),
    ('ASTRA-DEMO-0261','ADDITIVE_MANUFACTURING'),
    ('ASTRA-CAND-0013','FORMING_HEAT_TREATMENT'), ('ASTRA-CAND-0014','FORMING_HEAT_TREATMENT'),
    ('ASTRA-CAND-0038','FORMING_HEAT_TREATMENT'), ('ASTRA-CAND-0071','FORMING_HEAT_TREATMENT'),
    ('ASTRA-DEMO-0262','FORMING_HEAT_TREATMENT'), ('ASTRA-DEMO-0263','FORMING_HEAT_TREATMENT'),
    ('ASTRA-DEMO-0264','FORMING_HEAT_TREATMENT'), ('ASTRA-DEMO-0265','FORMING_HEAT_TREATMENT'),
    ('ASTRA-DEMO-0221','NDT_ULTRASONIC'), ('ASTRA-DEMO-0222','NDT_ULTRASONIC'),
    ('ASTRA-DEMO-0223','NDT_ULTRASONIC'), ('ASTRA-DEMO-0224','NDT_ULTRASONIC'),
    ('ASTRA-DEMO-0219','MASS_PROPERTIES'), ('ASTRA-DEMO-0220','MASS_PROPERTIES'),
    ('ASTRA-CAND-0023','MATERIAL_ANALYSIS')
) AS m(external_id, code) ON m.external_id = e.external_id
WHERE NOT EXISTS (SELECT 1 FROM equipment_test_types t WHERE t.equipment_id = e.id);

-- 4) 소프트웨어는 시험 장비가 아니므로 목록에서 숨김 ---------------------------
UPDATE equipment SET is_active = FALSE
WHERE external_id IN ('ASTRA-CAND-0028','ASTRA-CAND-0029','ASTRA-CAND-0030','ASTRA-CAND-0031');
