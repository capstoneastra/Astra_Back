"""ASTRA 장비 JSON(v0.6) -> PostgreSQL seed.sql 변환기

사용법:
    python json_to_seed.py <Equip_json 압축 푼 폴더> <출력 seed.sql>

입력 파일: astra_equipment_with_mock_v06.json, simulation_presets_all_71.json
schema.sql 을 먼저 실행한 DB에 seed.sql 을 실행하면 됩니다.
"""
import json
import re
import sys
from pathlib import Path

TEST_TYPE_NAMES = {
    # code: (한글명, 검색 키워드)
    "VIBRATION": ("진동시험", ["진동", "가진", "sine", "random", "공진"]),
    "TEMPERATURE_HUMIDITY": ("온습도시험", ["온도", "습도", "열", "환경", "챔버"]),
    "TENSILE": ("인장시험", ["인장", "강도", "UTM"]),
    "COMPRESSION": ("압축시험", ["압축", "UTM"]),
    "HARDNESS": ("경도시험", ["경도", "비커스", "로크웰"]),
    "CHARPY_IMPACT": ("충격시험(샤르피)", ["충격", "샤르피", "charpy"]),
    "DIMENSIONAL_INSPECTION": ("치수·형상 측정", ["치수", "3차원", "CMM", "형상"]),
    "MATERIAL_ANALYSIS": ("재료·성분 분석", ["성분", "분석", "NMR", "재료"]),
    "MICROSCOPY": ("현미경 관찰", ["현미경", "SEM", "미세조직"]),
    "X_RAY_INSPECTION": ("X선 비파괴검사", ["X선", "엑스레이", "CT", "비파괴"]),
    "ELECTRICAL_RF_ANALYSIS": ("전기·RF 측정", ["RF", "주파수", "안테나", "전자파", "GHz"]),
    "PROCESS_MEASUREMENT": ("공정 계측", ["공정", "계측", "모니터링"]),
    "BATTERY_TEST": ("배터리 시험", ["배터리", "충방전", "전지"]),
    "ACOUSTIC_NOISE": ("음향·소음 시험", ["소음", "음향", "dB"]),
    "POWER_SUPPLY": ("전원 시험", ["전원", "DC", "전력"]),
}


# 시험유형별 조건 규칙 -> test_condition_rules
# (condition_key, label, value_type, unit, is_required, spec_key, comparator)
# spec_key 는 원본 장비 JSON 의 사양 key 를 그대로 사용 (단위 없는 snake_case)
# RANGE_WITHIN: 요구 값/범위가 장비 범위 안 · MAX_LIMIT: 요구값 ≤ 장비값 · MIN_LIMIT: 장비값 ≤ 요구값 · NONE: 비교 안 함
_SIZE_TO = lambda prefix: [
    ("sample_size_x", "시료 크기 X", "NUMBER", "mm", True, f"{prefix}_x", "MAX_LIMIT"),
    ("sample_size_y", "시료 크기 Y", "NUMBER", "mm", True, f"{prefix}_y", "MAX_LIMIT"),
    ("sample_size_z", "시료 크기 Z", "NUMBER", "mm", True, f"{prefix}_z", "MAX_LIMIT"),
]
_LOAD = [
    ("test_load", "시험 하중", "NUMBER", "kN", True, "max_load", "MAX_LIMIT"),
    ("stroke", "필요 변위(스트로크)", "NUMBER", "mm", False, "crosshead_travel", "MAX_LIMIT"),
    ("specimen_material", "시편 재질", "TEXT", None, False, None, "NONE"),
]
_ELEC = [
    ("voltage", "시험 전압", "RANGE", "V", True, "voltage", "RANGE_WITHIN"),
    ("current", "시험 전류", "NUMBER", "A", True, "max_current", "MAX_LIMIT"),
]
CONDITION_RULES = {
    "VIBRATION": [
        ("sample_mass", "시료 질량", "NUMBER", "kg", True, "max_payload", "MAX_LIMIT"),
        ("fixture_mass", "고정구 질량", "NUMBER", "kg", True, "max_fixture_mass", "MAX_LIMIT"),
        ("frequency", "주파수 범위", "RANGE", "Hz", True, "frequency", "RANGE_WITHIN"),
        ("excitation_axis", "가진 축", "TEXT", None, False, None, "NONE"),
    ],
    "TEMPERATURE_HUMIDITY": [
        ("temperature", "온도 범위", "RANGE", "°C", True, "temperature", "RANGE_WITHIN"),
        ("humidity", "습도 범위", "RANGE", "%RH", False, "humidity", "RANGE_WITHIN"),
        ("temperature_change_rate", "온도 변화율", "NUMBER", "°C/min", False, "temperature_change_rate", "MAX_LIMIT"),
        ("sample_size_x", "시료 크기 X", "NUMBER", "mm", True, "chamber_width", "MAX_LIMIT"),
        ("sample_size_y", "시료 크기 Y", "NUMBER", "mm", True, "chamber_depth", "MAX_LIMIT"),
        ("sample_size_z", "시료 크기 Z", "NUMBER", "mm", True, "chamber_height", "MAX_LIMIT"),
    ],
    "TENSILE": _LOAD,
    "COMPRESSION": _LOAD,
    "HARDNESS": [("test_force", "시험 하중", "NUMBER", "kgf", True, "test_force", "RANGE_WITHIN")],
    "CHARPY_IMPACT": [("impact_energy", "필요 충격 에너지", "NUMBER", "J", True, "impact_energy", "MAX_LIMIT")],
    "DIMENSIONAL_INSPECTION": _SIZE_TO("measurement_range"),
    "PROCESS_MEASUREMENT": [("resolution", "필요 분해능", "NUMBER", "mm", True, "measurement_resolution", "MIN_LIMIT")],
    "MATERIAL_ANALYSIS": [
        ("analysis_item", "분석 항목", "TEXT", None, True, None, "NONE"),
        ("daily_sample_count", "하루 시료 수", "NUMBER", "samples/day", False, "sample_capacity", "MAX_LIMIT"),
    ],
    "MICROSCOPY": [("magnification", "필요 배율", "NUMBER", "x", True, "magnification", "MAX_LIMIT")],
    "X_RAY_INSPECTION": [("xray_voltage", "필요 관전압", "NUMBER", "kV", True, "xray_voltage", "MAX_LIMIT")],
    "ELECTRICAL_RF_ANALYSIS": [("frequency", "주파수 범위", "RANGE", "GHz", True, "frequency", "RANGE_WITHIN")],
    "BATTERY_TEST": _ELEC,
    "POWER_SUPPLY": _ELEC,
    "ACOUSTIC_NOISE": [("sound_level", "측정 소음 범위", "RANGE", "dB", True, "sound_level", "RANGE_WITHIN")],
}


def q(v):
    """SQL literal"""
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, (dict, list)):
        return q(json.dumps(v, ensure_ascii=False)) + "::jsonb"
    return "'" + str(v).replace("'", "''") + "'"


def arr(values):
    return "ARRAY[" + ",".join(q(v) for v in values) + "]::text[]" if values else "'{}'::text[]"


def parse_fee(fee_text):
    if not fee_text:
        return None, None
    if "협의" in fee_text:
        return None, "NEGOTIABLE"
    m = re.search(r"([\d,]+)\s*KRW/(hour|day|case)", fee_text, re.I)
    if not m:
        return None, None
    return int(m.group(1).replace(",", "")), m.group(2).upper()


def main(src: Path, out: Path):
    data = json.loads((src / "astra_equipment_with_mock_v06.json").read_text(encoding="utf-8-sig"))
    sims = json.loads((src / "simulation_presets_all_71.json").read_text(encoding="utf-8-sig"))
    eq = data["equipment"]
    lines = ["-- generated by tools/json_to_seed.py  (source schema " + data["schemaVersion"] + ")",
             "BEGIN;", ""]

    # 1) 기관
    insts = {}
    for e in eq:
        insts.setdefault(e["institution"], e["region"])
        if e["region"] and not insts[e["institution"]]:
            insts[e["institution"]] = e["region"]
    lines.append("-- institutions")
    for name, region in insts.items():
        lines.append(
            f"INSERT INTO organizations (org_type, name, region) VALUES ('INSTITUTION', {q(name)}, {q(region)});")

    # 2) 시험유형
    lines.append("\n-- test_types")
    used = sorted({t for e in eq for t in e["testTypesDemo"]})
    for i, code in enumerate(used):
        name, kws = TEST_TYPE_NAMES.get(code, (code, []))
        lines.append(f"INSERT INTO test_types (code, name_ko, keywords, display_order) "
                     f"VALUES ({q(code)}, {q(name)}, {arr(kws)}, {i});")

    # 2-1) 시험유형별 조건 규칙
    lines.append("\n-- test_condition_rules")
    spec_keys = {s["key"] for e in eq for s in e["specifications"] + e["demoSpecifications"]}
    missing = sorted(set(used) - CONDITION_RULES.keys())
    assert not missing, f"조건 규칙이 없는 시험유형: {missing}"
    rule_count = 0
    for code in used:
        for order, (key, label, vtype, unit, required, spec_key, comparator) in enumerate(CONDITION_RULES[code]):
            assert spec_key is None or spec_key in spec_keys, f"{code}.{key}: 데이터에 없는 사양 키 {spec_key}"
            lines.append(
                "INSERT INTO test_condition_rules (test_type_code, condition_key, label, value_type, unit, "
                "is_required, spec_key, comparator, display_order) VALUES ("
                + ", ".join([q(code), q(key), q(label), q(vtype), q(unit), q(required), q(spec_key),
                             q(comparator), str(order)]) + ");")
            rule_count += 1

    # 3) 장비 + 사양 + 시험유형 매핑
    lines.append("\n-- equipment")
    for e in eq:
        u = e["usage"]
        fee_text = u.get("feeText") or u.get("cost")
        fee_amount, fee_unit = parse_fee(fee_text if isinstance(fee_text, str) else None)
        src_info = e["source"]
        inst_sub = f"(SELECT id FROM organizations WHERE org_type='INSTITUTION' AND name={q(e['institution'])})"
        lines.append(
            "INSERT INTO equipment (external_id, institution_id, name, model, manufacturer, category, equipment_type, "
            "region, access_scope, fee_text, fee_amount, fee_unit, duration_text, utilization_rate, availability, "
            "source_type, source_url, source_file, source_page, verification_status, spec_verification_status, "
            "aerospace_relevance, is_mock, simulation_capability) VALUES ("
            + ", ".join([
                q(e["internalId"]), inst_sub, q(e["name"]), q(e["model"]), q(e["manufacturer"]), q(e["category"]),
                q(e["equipmentTypeDemo"]), q(e["region"] or insts[e["institution"]]),
                q(u.get("accessScope") or "UNKNOWN"), q(fee_text if isinstance(fee_text, str) else None),
                q(fee_amount), q(fee_unit), q(u.get("duration")), q(u.get("utilizationRate")),
                q(u.get("availability") or "UNKNOWN"),
                q(src_info["type"]), q(src_info["url"]), q(src_info["file"]), q(src_info["page"]),
                q(e["verificationStatus"]), q(e["specVerificationStatus"]), q(e["aerospaceRelevance"]),
                q(e["demoDataPolicy"]["isMock"]), q(e["simulationCapability"]),
            ]) + ");")
        eq_sub = f"(SELECT id FROM equipment WHERE external_id={q(e['internalId'])})"
        for source_type, specs in (("REAL", e["specifications"]), ("MOCK", e["demoSpecifications"])):
            for s in specs:
                lines.append(
                    "INSERT INTO equipment_specs (equipment_id, spec_key, label, value_num, min_num, max_num, unit, "
                    "source_type, verification_status, usage_scope) VALUES ("
                    + ", ".join([eq_sub, q(s["key"]), q(s["label"]), q(s.get("value")), q(s.get("min")),
                                 q(s.get("max")), q(s.get("unit")), q(source_type),
                                 q(s.get("verificationStatus", "NOT_VERIFIED")),
                                 q("DEMO_ONLY" if source_type == "MOCK" else "GENERAL")]) + ");")
        for t in e["testTypesDemo"]:
            lines.append(f"INSERT INTO equipment_test_types (equipment_id, test_type_code) VALUES ({eq_sub}, {q(t)});")

    # 4) 시뮬레이션 모델 / 프리셋
    lines.append("\n-- simulation_models")
    for m in sims["modelCatalog"]:
        lines.append(
            "INSERT INTO simulation_models (model_type, description, required_input_fields, limitation, capability, "
            f"model_version) VALUES ({q(m['modelType'])}, {q(m['description'])}, {arr(m['requiredInputFields'])}, "
            f"{q(m['limitation'])}, {q(m['capability'])}, {q(sims['schemaVersion'])});")
    lines.append("\n-- simulation_presets")
    for p in sims["presets"]:
        lines.append(
            "INSERT INTO simulation_presets (preset_id, equipment_id, model_type, simulation_type, input_parameters, "
            "specs_snapshot, assumptions, is_mock) VALUES ("
            + ", ".join([q(p["presetId"]), f"(SELECT id FROM equipment WHERE external_id={q(p['equipmentId'])})",
                         q(p["modelType"]), q(p["simulationType"]), q(p["inputParameters"]),
                         q(p.get("equipmentDemoSpecsSnapshot")), q(p.get("assumptions")), "TRUE"]) + ");")

    lines += ["", "COMMIT;", ""]
    out.write_text("\n".join(lines), encoding="utf-8")
    print(f"institutions={len(insts)} test_types={len(used)} condition_rules={rule_count} equipment={len(eq)} "
          f"models={len(sims['modelCatalog'])} presets={len(sims['presets'])} -> {out}")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
