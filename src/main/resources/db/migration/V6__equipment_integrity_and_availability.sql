-- BE-A/B/C 보완. V6 사용 확인: 2026-11-14. V1~V5는 변경하지 않는다.
CREATE TABLE spec_definitions (
    spec_key VARCHAR(80) PRIMARY KEY,
    name_ko VARCHAR(100) NOT NULL,
    base_unit VARCHAR(30),
    value_type VARCHAR(10) NOT NULL CHECK (value_type IN ('NUMBER','RANGE','TEXT')),
    UNIQUE (spec_key, base_unit)
);
-- 기존 29개 키와 실제 seed의 명칭을 사용. 주파수의 기준은 Hz.
INSERT INTO spec_definitions (spec_key,name_ko,base_unit,value_type) VALUES
    ('chamber_depth','내부 깊이','mm','NUMBER'),
    ('chamber_height','내부 높이','mm','NUMBER'),
    ('chamber_volume','내부 체적 예시','L','NUMBER'),
    ('chamber_width','내부 폭','mm','NUMBER'),
    ('crosshead_travel','시험 스트로크 예시','mm','NUMBER'),
    ('frequency','주파수 범위','Hz','RANGE'),
    ('humidity','상대습도 범위','%RH','RANGE'),
    ('impact_energy','최대 충격 에너지 예시','J','NUMBER'),
    ('magnification','배율 예시','x','NUMBER'),
    ('max_current','최대 전류 예시','A','NUMBER'),
    ('max_fixture_mass','고정구 질량 허용 예시','kg','NUMBER'),
    ('max_load','최대 하중 예시','kN','NUMBER'),
    ('max_payload','최대 시료 질량','kg','NUMBER'),
    ('max_press_force','가압력 예시','kN','NUMBER'),
    ('measurement_range_x','측정 범위 X 예시','mm','NUMBER'),
    ('measurement_range_y','측정 범위 Y 예시','mm','NUMBER'),
    ('measurement_range_z','측정 범위 Z 예시','mm','NUMBER'),
    ('measurement_resolution','측정 분해능 예시','mm','NUMBER'),
    ('rated_sine_force','정현파 가진력 예시','kN','NUMBER'),
    ('robot_reach','작업 반경 예시','mm','NUMBER'),
    ('sample_capacity','시료 처리량 예시','samples/day','NUMBER'),
    ('sound_level','소음 측정 범위 예시','dB','RANGE'),
    ('temperature','온도 범위','°C','RANGE'),
    ('temperature_change_rate','온도 변화율','°C/min','NUMBER'),
    ('test_force','시험하중 예시','kgf','RANGE'),
    ('voltage','전압 범위 예시','V','RANGE'),
    ('work_envelope_x','작업영역 X 예시','mm','NUMBER'),
    ('work_envelope_y','작업영역 Y 예시','mm','NUMBER'),
    ('xray_voltage','X-Ray 전압 예시','kV','NUMBER');

ALTER TABLE equipment_specs ADD COLUMN original_unit VARCHAR(30);
UPDATE equipment_specs SET original_unit = unit;
UPDATE equipment_specs SET value_num = value_num * 1000000000,
    min_num = min_num * 1000000000, max_num = max_num * 1000000000, unit = 'Hz'
WHERE spec_key = 'frequency' AND unit = 'GHz';
UPDATE test_condition_rules SET unit = 'Hz' WHERE spec_key = 'frequency';
ALTER TABLE equipment_specs
    ADD CONSTRAINT fk_specs_definition FOREIGN KEY (spec_key) REFERENCES spec_definitions(spec_key),
    ADD CONSTRAINT fk_specs_base_unit FOREIGN KEY (spec_key, unit) REFERENCES spec_definitions(spec_key, base_unit),
    ADD CONSTRAINT ck_specs_value CHECK (
        (value_num IS NOT NULL AND min_num IS NULL AND max_num IS NULL AND value_text IS NULL)
        OR (value_num IS NULL AND min_num IS NOT NULL AND max_num IS NOT NULL AND min_num <= max_num AND value_text IS NULL)
        OR (value_num IS NULL AND min_num IS NULL AND max_num IS NULL AND value_text IS NOT NULL AND length(trim(value_text)) > 0));
ALTER TABLE test_condition_rules ADD CONSTRAINT fk_rules_definition
    FOREIGN KEY (spec_key) REFERENCES spec_definitions(spec_key);
ALTER TABLE test_condition_rules ADD CONSTRAINT fk_rules_base_unit
    FOREIGN KEY (spec_key,unit) REFERENCES spec_definitions(spec_key,base_unit);

CREATE FUNCTION validate_equipment_spec() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE d spec_definitions;
BEGIN
    SELECT * INTO d FROM spec_definitions WHERE spec_key = NEW.spec_key;
    IF NOT FOUND THEN RETURN NEW; END IF; -- FK가 알 수 없는 키를 거절한다.
    IF NEW.unit IS DISTINCT FROM d.base_unit
       OR (d.value_type = 'NUMBER' AND NEW.value_num IS NULL)
       OR (d.value_type = 'RANGE' AND (NEW.min_num IS NULL OR NEW.max_num IS NULL))
       OR (d.value_type = 'TEXT' AND NEW.value_text IS NULL) THEN
        RAISE EXCEPTION '사양 사전의 값 형식/기준 단위와 일치하지 않습니다.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tr_validate_equipment_spec BEFORE INSERT OR UPDATE ON equipment_specs
    FOR EACH ROW EXECUTE FUNCTION validate_equipment_spec();

-- 조건의 동의어는 시험유형+조건 키에 연결한다. 사양 키와 조건 키를 혼동하지 않는다.
CREATE TABLE condition_aliases (
    test_type_code VARCHAR(50) NOT NULL,
    condition_key VARCHAR(80) NOT NULL,
    alias VARCHAR(100) NOT NULL,
    PRIMARY KEY (test_type_code, condition_key, alias),
    FOREIGN KEY (test_type_code, condition_key) REFERENCES test_condition_rules(test_type_code, condition_key) ON DELETE CASCADE
);
INSERT INTO condition_aliases VALUES
    ('VIBRATION','sample_mass','무게'), ('VIBRATION','sample_mass','중량'),
    ('VIBRATION','sample_mass','시료 질량'), ('VIBRATION','fixture_mass','고정구 무게'),
    ('VIBRATION','frequency','주파수'), ('VIBRATION','frequency','진동수');

-- 공인 요구는 수치 comparator를 늘리지 않고 기관 능력으로 별도 판정한다.
INSERT INTO test_condition_rules(test_type_code,condition_key,label,value_type,unit,is_required,spec_key,comparator,display_order)
SELECT code,'accredited_required','공인성적서 필요 여부','TEXT',NULL,FALSE,NULL,'NONE',100 FROM test_types;

ALTER TABLE equipment
    ADD COLUMN availability_updated_at TIMESTAMPTZ,
    ADD COLUMN availability_updated_by BIGINT REFERENCES users(id),
    ADD COLUMN availability_note VARCHAR(500),
    ADD COLUMN availability_reminded_at TIMESTAMPTZ,
    ADD COLUMN lead_time_days INT CHECK (lead_time_days >= 0),
    ADD COLUMN operation_mode VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (operation_mode IN ('INSTITUTION_OPERATED','SUPERVISED_SELF_SERVICE','SELF_SERVICE','UNKNOWN'));
-- 기존 정보 갱신일은 상태 확인일이 아니다. UNKNOWN에는 확인일을 만들어 넣지 않는다.
CREATE TABLE equipment_downtimes (
    id BIGSERIAL PRIMARY KEY,
    equipment_id BIGINT NOT NULL REFERENCES equipment(id),
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    created_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (end_at > start_at)
);
CREATE INDEX idx_downtimes_equipment_period ON equipment_downtimes(equipment_id, start_at, end_at);
ALTER TABLE equipment_test_types
    ADD COLUMN is_accredited BOOLEAN,
    ADD COLUMN accreditation_no VARCHAR(100),
    ADD COLUMN accreditation_valid_until DATE,
    ADD CONSTRAINT ck_accreditation_evidence CHECK (is_accredited IS DISTINCT FROM TRUE
        OR (accreditation_no IS NOT NULL AND length(trim(accreditation_no)) > 0 AND accreditation_valid_until IS NOT NULL));
ALTER TABLE organizations ADD COLUMN has_secure_facility BOOLEAN, ADD COLUMN security_note VARCHAR(1000);

-- 두 테이블의 일정 경합을 같은 장비 행 잠금으로 직렬화한다. 서비스 외 쓰기도 보호한다.
CREATE FUNCTION guard_equipment_schedule() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM id FROM equipment WHERE id = NEW.equipment_id FOR UPDATE;
    IF TG_TABLE_NAME = 'reservations' THEN
        IF NEW.status = 'CONFIRMED' AND EXISTS (
            SELECT 1 FROM equipment_downtimes d WHERE d.equipment_id=NEW.equipment_id
                AND d.start_at<NEW.end_at AND d.end_at>NEW.start_at) THEN
            RAISE EXCEPTION '확정 예약과 정비 일정이 겹칩니다.' USING ERRCODE='23P01';
        END IF;
    ELSIF EXISTS (SELECT 1 FROM reservations r WHERE r.equipment_id=NEW.equipment_id
            AND r.status='CONFIRMED' AND r.start_at<NEW.end_at AND r.end_at>NEW.start_at) THEN
        RAISE EXCEPTION '정비 일정과 확정 예약이 겹칩니다.' USING ERRCODE='23P01';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tr_reservation_downtime BEFORE INSERT OR UPDATE ON reservations
    FOR EACH ROW EXECUTE FUNCTION guard_equipment_schedule();
CREATE TRIGGER tr_downtime_reservation BEFORE INSERT OR UPDATE ON equipment_downtimes
    FOR EACH ROW EXECUTE FUNCTION guard_equipment_schedule();

-- 시연 상태임을 명시한다. 실제 기관이 확인한 가용성/공인정보로 가장하지 않는다.
UPDATE equipment SET availability = CASE external_id
    WHEN 'ASTRA-CAND-0045' THEN 'AVAILABLE' WHEN 'ASTRA-CAND-0046' THEN 'BUSY'
    WHEN 'ASTRA-CAND-0050' THEN 'MAINTENANCE' WHEN 'ASTRA-CAND-0051' THEN 'UNAVAILABLE' END,
    availability_updated_at = now(), availability_note = 'DEMO: 가용성 화면 시연용 상태. 실제 이용 전 기관 확인 필요.'
WHERE external_id IN ('ASTRA-CAND-0045','ASTRA-CAND-0046','ASTRA-CAND-0050','ASTRA-CAND-0051')
  AND availability = 'UNKNOWN' AND is_mock AND availability_updated_by IS NULL;
