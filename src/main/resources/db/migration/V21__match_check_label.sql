-- =====================================================================
-- V21: 매칭 조건 판정에 한글 조건 이름 저장 (BE-A, 2026-11-27)
--  화면에 sample_mass: UNKNOWN 처럼 영문 키가 보이던 문제. 새 판정은 서버가 label 을 채우고,
--  이미 저장된 판정은 시험유형 조건 규칙의 이름으로 채움 (같은 조건 키는 시험유형이 달라도 이름이 같음)
-- =====================================================================
ALTER TABLE match_condition_checks ADD COLUMN label VARCHAR(100);

UPDATE match_condition_checks m
SET label = r.label
FROM (SELECT DISTINCT ON (condition_key) condition_key, label
      FROM test_condition_rules ORDER BY condition_key, test_type_code) r
WHERE m.label IS NULL AND r.condition_key = m.condition_key;

-- 이미 저장된 추천 사유 문구의 영문 판정도 한글로 (예: "sample_mass: UNKNOWN" → "시료 질량: 확인 필요")
UPDATE match_results mr
SET reason = x.reason
FROM (SELECT mr2.id,
             (SELECT string_agg(coalesce(c.label, c.condition_key) || ': '
                     || CASE c.result WHEN 'MET' THEN '충족' WHEN 'NOT_MET' THEN '미충족' ELSE '확인 필요' END, ', ' ORDER BY c.id)
              FROM match_condition_checks c WHERE c.match_result_id = mr2.id)
             || substr(mr2.reason, strpos(mr2.reason, '. ')) AS reason
      FROM match_results mr2
      WHERE mr2.reason ~ '^[a-z_]+: (MET|NOT_MET|UNKNOWN)' AND strpos(mr2.reason, '. ') > 0) x
WHERE mr.id = x.id AND x.reason IS NOT NULL;
