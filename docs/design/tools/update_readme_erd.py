"""README.md 의 ERD 블록(<!-- ERD:START --> ~ <!-- ERD:END -->)을 schema.sql 기준으로 다시 생성

사용법: python docs/design/tools/update_readme_erd.py   (저장소 루트에서)
"""
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
readme = REPO / "README.md"
erd = subprocess.run([sys.executable, str(Path(__file__).with_name("schema_to_mermaid.py"))],
                     capture_output=True, text=True, encoding="utf-8", check=True).stdout
erd = erd.replace("\r\n", "\n").strip()
text = readme.read_text(encoding="utf-8")
start = text.index("<!-- ERD:START")
start = text.index("\n", start) + 1
end = text.index("<!-- ERD:END -->")
readme.write_text(text[:start] + "\n" + erd + "\n\n" + text[end:], encoding="utf-8", newline="\n")
print(f"README ERD 갱신: {erd.count('```mermaid')}개 다이어그램")
