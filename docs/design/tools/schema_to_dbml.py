"""schema.sql -> erd.dbml 자동 생성 (dbdiagram.io 용)

사용법: python tools/schema_to_dbml.py   (astra-design 폴더에서)
schema.sql 이 기준이므로 erd.dbml 은 손으로 고치지 말고 이 스크립트로 다시 만드세요.
필요 패키지: pip install pglast
"""
import re
from pathlib import Path

import pglast
from pglast.stream import RawStream
from pglast import ast

ROOT = Path(__file__).resolve().parent.parent
schema = (ROOT / "schema.sql").read_text(encoding="utf-8")

GROUPS = [
    ("user_org", ["organizations", "users", "refresh_tokens", "email_verifications", "notifications"]),
    ("equipment_domain", ["equipment", "equipment_specs", "equipment_change_logs", "test_types",
                          "equipment_test_types", "test_condition_rules", "spec_definitions", "condition_aliases", "equipment_downtimes"]),
    ("ai_domain", ["chat_sessions", "chat_messages", "test_requirements", "requirement_conditions",
                   "match_runs", "match_results", "match_condition_checks", "ai_pipeline_runs"]),
    ("request_domain", ["test_requests", "test_request_revisions", "request_status_history",
                        "supplement_requests", "attachments", "reservations"]),
    ("simulation_domain", ["simulation_models", "simulation_presets", "simulation_runs"]),
]

TYPE_MAP = {"int8": "bigint", "int4": "int", "bool": "boolean", "numeric": "numeric", "text": "text",
            "varchar": "varchar", "timestamptz": "timestamptz", "date": "date", "jsonb": "jsonb"}


def col_type(tn):
    name = [n.sval for n in tn.names if n.sval != "pg_catalog"][-1]
    t = TYPE_MAP.get(name, name)
    if t == "varchar" and tn.typmods:
        t = f"varchar({tn.typmods[0].val.ival})"
    if t == "numeric" and tn.typmods:
        t = "numeric(" + ",".join(str(m.val.ival) for m in tn.typmods) + ")"
    if tn.arrayBounds:
        t += "[]"
    if name == "serial8" or name == "bigserial":
        t = "bigint"
    return t


# 컬럼 주석: schema 텍스트에서 "컬럼명 ... -- 주석" + 다음 줄의 순수 주석
comments = {}
for tname, body in re.findall(r"CREATE TABLE (\w+) \((.*?)\n\);", schema, re.S):
    last = None
    for line in body.split("\n"):
        s = line.strip()
        m = re.match(r"(\w+)\s+[A-Z]", s)
        if m and m.group(1) not in ("UNIQUE", "PRIMARY", "CHECK", "CONSTRAINT"):
            last = m.group(1)
            if "--" in s:
                comments[(tname, last)] = s.split("--", 1)[1].strip()
        elif s.startswith("--") and last:
            comments[(tname, last)] = (comments.get((tname, last), "") + " " + s[2:].strip()).strip()
        elif s.startswith(("CHECK", "CONSTRAINT", "UNIQUE", "PRIMARY")):
            last = None

tables = {}
order = []
for raw in pglast.parse_sql(schema):
    st = raw.stmt
    if isinstance(st, ast.CreateStmt):
        t = st.relation.relname
        order.append(t)
        info = {"cols": [], "pk": [], "uniques": [], "notes": []}
        for el in st.tableElts:
            if isinstance(el, ast.ColumnDef):
                c = {"name": el.colname, "type": col_type(el.typeName), "settings": [], "enum": None}
                for con in el.constraints or []:
                    ct = con.contype.name if hasattr(con.contype, "name") else str(con.contype)
                    if ct == "CONSTR_PRIMARY":
                        c["settings"].append("pk")
                    elif ct == "CONSTR_UNIQUE":
                        c["settings"].append("unique")
                    elif ct == "CONSTR_NOTNULL":
                        c["settings"].append("not null")
                    elif ct == "CONSTR_FOREIGN":
                        c["settings"].append(f"ref: > {con.pktable.relname}.{con.pk_attrs[0].sval}")
                    elif ct == "CONSTR_DEFAULT":
                        d = RawStream()(con.raw_expr)
                        c["settings"].append(f"default: `{d}`")
                    elif ct == "CONSTR_CHECK":
                        vals = re.findall(r"'(\w+)'", RawStream()(con.raw_expr))
                        if vals:
                            c["enum"] = vals
                info["cols"].append(c)
            elif isinstance(el, ast.Constraint):
                ct = el.contype.name
                if ct == "CONSTR_PRIMARY":
                    info["pk"] = [k.sval for k in el.keys]
                elif ct == "CONSTR_UNIQUE":
                    info["uniques"].append(([k.sval for k in el.keys], None))
                elif ct == "CONSTR_CHECK":
                    expr = RawStream()(el.raw_expr)
                    m = re.match(r"(\w+) IN \((.*)\)", expr)
                    if m:
                        for c in info["cols"]:
                            if c["name"] == m.group(1):
                                c["enum"] = re.findall(r"'(\w+)'", m.group(2))
                    else:
                        info["notes"].append(f"CHECK ({expr})")
                elif ct == "CONSTR_EXCLUSION":
                    info["notes"].append(f"{el.conname}: 같은 장비의 CONFIRMED 예약끼리 시간 겹침 금지 (EXCLUDE USING gist)")
        tables[t] = info
    elif isinstance(st, ast.IndexStmt) and st.unique:
        t = st.relation.relname
        cols = [p.name for p in st.indexParams]
        where = RawStream()(st.whereClause) if st.whereClause else None
        tables[t]["uniques"].append((cols, where, st.idxname))

grouped = {t for _, ts in GROUPS for t in ts}
assert grouped == set(tables), f"GROUPS 불일치: 누락 {set(tables) - grouped}, 없는 테이블 {grouped - set(tables)}"

out = ["// ASTRA ERD — tools/schema_to_dbml.py 가 schema.sql 에서 자동 생성 (직접 수정 금지)",
       "// https://dbdiagram.io 에 붙여넣으면 다이어그램이 그려집니다.", "",
       "Project ASTRA { database_type: 'PostgreSQL' }", ""]
for g, ts in GROUPS:
    out.append(f"TableGroup {g} {{ {' '.join(ts)} }}")
out.append("")
for t in order:
    info = tables[t]
    out.append(f"Table {t} {{")
    for c in info["cols"]:
        settings = list(c["settings"])
        note = comments.get((t, c["name"]), "")
        if c["enum"]:
            note = (" | ".join(c["enum"]) + ("  — " + note if note else "")).strip()
        if note:
            settings.append("note: '" + note.replace("'", "\\'") + "'")
        out.append(f"  {c['name']} {c['type']}" + (f" [{', '.join(settings)}]" if settings else ""))
    idx = []
    if info["pk"]:
        idx.append(f"    ({', '.join(info['pk'])}) [pk]")
    for u in info["uniques"]:
        cols, where = u[0], u[1]
        name = u[2] if len(u) > 2 else None
        opts = ["unique"] + ([f"name: '{name}'"] if name else []) + ([f"note: 'WHERE " + where.replace("'", "\\'") + "'"] if where else [])
        idx.append(f"    ({', '.join(cols)}) [{', '.join(opts)}]")
    if idx:
        out += ["  indexes {", *idx, "  }"]
    if info["notes"]:
        out.append("  Note: '" + " / ".join(info["notes"]).replace("'", "\\'") + "'")
    out += ["}", ""]
(ROOT / "erd.dbml").write_text("\n".join(out), encoding="utf-8")
print(f"tables={len(order)} -> erd.dbml")
