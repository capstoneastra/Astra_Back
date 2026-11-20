"""schema.sql -> Mermaid ERD (README 용, GitHub 에서 그림으로 렌더링)

사용법: python docs/design/tools/schema_to_mermaid.py > erd.md
README 의 ERD 블록은 이 출력으로 교체합니다. 필요 패키지: pip install pglast
"""
import re
import sys
from pathlib import Path

import pglast
from pglast import ast
from pglast.stream import RawStream

ROOT = Path(__file__).resolve().parent.parent
schema = (ROOT / "schema.sql").read_text(encoding="utf-8")

DOMAINS = [
    ("사용자 · 조직", ["organizations", "users", "refresh_tokens", "email_verifications", "notifications"]),
    ("장비 · 시험유형", ["equipment", "equipment_specs", "equipment_change_logs", "test_types",
                    "equipment_test_types", "test_condition_rules", "spec_definitions", "condition_aliases", "equipment_downtimes"]),
    ("AI 상담 · 시험조건 · 매칭 · 자동 추진", ["chat_sessions", "chat_messages", "test_requirements",
                                      "requirement_conditions", "match_runs", "match_results",
                                      "match_condition_checks", "ai_pipeline_runs"]),
    ("시험 의뢰 · 검토 · 보완 · 예약 · 결과", ["test_requests", "test_request_revisions", "request_status_history",
                                "supplement_requests", "attachments", "reservations", "test_results"]),
    ("가상 시뮬레이션", ["simulation_models", "simulation_presets", "simulation_runs"]),
]
SKIP_COLS = {"created_at", "updated_at"}
TYPE_MAP = {"int8": "bigint", "int4": "int", "bool": "boolean", "numeric": "numeric", "text": "text",
            "varchar": "varchar", "timestamptz": "timestamptz", "date": "date", "jsonb": "jsonb",
            "bigserial": "bigint"}

tables, order = {}, []
for raw in pglast.parse_sql(schema):
    st = raw.stmt
    if not isinstance(st, ast.CreateStmt):
        continue
    t = st.relation.relname
    order.append(t)
    cols, fks, pk, uq = [], [], set(), set()
    for el in st.tableElts:
        if isinstance(el, ast.ColumnDef):
            name = [n.sval for n in el.typeName.names if n.sval != "pg_catalog"][-1]
            typ = TYPE_MAP.get(name, name) + ("_array" if el.typeName.arrayBounds else "")
            col = {"name": el.colname, "type": typ, "notnull": False, "enum": None}
            for con in el.constraints or []:
                ct = con.contype.name
                if ct == "CONSTR_PRIMARY":
                    pk.add(el.colname)
                elif ct == "CONSTR_UNIQUE":
                    uq.add(el.colname)
                elif ct == "CONSTR_NOTNULL":
                    col["notnull"] = True
                elif ct == "CONSTR_FOREIGN":
                    fks.append((el.colname, con.pktable.relname))
                elif ct == "CONSTR_CHECK":
                    vals = re.findall(r"'(\w+)'", RawStream()(con.raw_expr))
                    col["enum"] = vals or None
            cols.append(col)
        elif isinstance(el, ast.Constraint):
            ct = el.contype.name
            if ct == "CONSTR_PRIMARY":
                pk.update(k.sval for k in el.keys)
            elif ct == "CONSTR_CHECK":
                m = re.match(r"(\w+) IN \((.*)\)", RawStream()(el.raw_expr))
                if m:
                    for c in cols:
                        if c["name"] == m.group(1):
                            c["enum"] = re.findall(r"'(\w+)'", m.group(2))
    tables[t] = {"cols": cols, "fks": fks, "pk": pk, "uq": uq}

assert {t for _, ts in DOMAINS for t in ts} == set(tables), "DOMAINS 와 schema 테이블이 다름"


def card(t, col):
    c = next(c for c in tables[t]["cols"] if c["name"] == col)
    return "||" if (c["notnull"] or col in tables[t]["pk"]) else "|o"


def entity(t, full=True):
    info = tables[t]
    fk_cols = {c for c, _ in info["fks"]}
    lines = [f"    {t} {{"]
    for c in info["cols"]:
        if not full and c["name"] not in info["pk"]:
            continue
        if full and c["name"] in SKIP_COLS:
            continue
        keys = [k for k, ok in (("PK", c["name"] in info["pk"]), ("FK", c["name"] in fk_cols),
                                ("UK", c["name"] in info["uq"])) if ok]
        comment = f' "{" / ".join(c["enum"])}"' if (full and c["enum"]) else ""
        lines.append(f"        {c['type']} {c['name']}{(' ' + ', '.join(keys)) if keys else ''}{comment}")
    lines.append("    }")
    return lines


out = []
# 1) 전체 관계 지도 (테이블 이름만)
pairs = []
for t in order:
    for col, ref in tables[t]["fks"]:
        if ref != t and (ref, t) not in [(a, b) for a, b, _ in pairs]:  # 자기 참조(승인자 등)는 전체도에서 생략
            pairs.append((ref, t, card(t, col)))
out += ["#### 전체 관계도", "", "```mermaid", "erDiagram"]
out += [f"    {a} {c}--o{{ {b} : \"\"" for a, b, c in pairs]
out += ["```", ""]
# 2) 도메인별 상세 (created_at / updated_at 생략)
for title, names in DOMAINS:
    stubs = sorted({ref for t in names for _, ref in tables[t]["fks"]} - set(names))
    out += [f"<details>\n<summary><b>{title}</b> ({len(names)}개 테이블)</summary>", "", "```mermaid", "erDiagram"]
    for t in names:
        out += entity(t)
    for s in stubs:
        out += entity(s, full=False)
    for t in names:
        for col, ref in tables[t]["fks"]:
            out.append(f"    {ref} {card(t, col)}--o{{ {t} : \"{col}\"")
    out += ["```", "", "</details>", ""]
sys.stdout.reconfigure(encoding="utf-8")
print("\n".join(out))
