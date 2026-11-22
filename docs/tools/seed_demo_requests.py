"""시연용 의뢰 데이터를 실제 API 로 채우는 스크립트 (= 백엔드 전체 흐름 점검)

새 DB 로 서버를 띄운 뒤 한 번 실행하면, 화면을 연결하자마자 보일 의뢰가 여러 상태로 생깁니다.
모든 단계가 실제 API 호출이라, 끝까지 성공하면 "백엔드는 이 순서로 된다"는 확인도 됩니다.

사용법 (저장소 루트에서, 파이썬 표준 라이브러리만 사용):
    python docs/tools/seed_demo_requests.py                       # http://localhost:8080, 비밀번호 12345678
    python docs/tools/seed_demo_requests.py --base http://서버주소:8080

만들어지는 의뢰 (제목 앞에 [시연]):
    작성 중(DRAFT) · 제출됨(SUBMITTED) · 검토 중 + 예상 이용료 · 추가 정보 요청됨 · 다시 냄(재제출)
    · 일정 조율 중(제안 1건) · 일정 확정 · 시험 완료(결과 + 성적서 파일) · 반려
    · 조직 간: 대학 → 기업 장비 의뢰, 기업 → 대학 장비 의뢰(검토 통과)
여러 번 실행하면 같은 의뢰가 또 생깁니다 (시연 DB 는 새로 만든 뒤 한 번만 실행).
"""
import argparse
import json
import sys
import time
import uuid
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
STEPS = []


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/") + "/api/v1"

    def call(self, method, path, body=None, token=None, raw=None, content_type="application/json"):
        data = raw if raw is not None else (None if body is None else json.dumps(body).encode())
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", content_type)
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                status, text = r.status, r.read().decode()
        except urllib.error.HTTPError as e:
            status, text = e.code, e.read().decode()
        try:
            js = json.loads(text) if text else {}
        except ValueError:
            js = {"raw": text}
        return status, js


def step(name, status, js, expect):
    ok = status in (expect if isinstance(expect, tuple) else (expect,))
    err = js.get("error") or {}
    STEPS.append((ok, name, status, err.get("code")))
    print(("  OK " if ok else "  !! ") + f"{name}: {status}" + (f" {err.get('code')} {err.get('message')}" if err else ""))
    if not ok:
        print("\n중단: 위 단계가 실패했습니다. 응답:", json.dumps(js, ensure_ascii=False)[:600])
        sys.exit(1)
    return js.get("data")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--password", default="12345678")
    args = ap.parse_args()
    api = Api(args.base)

    print("[로그인]")
    tok = {}
    for who in ["company", "company2", "manager1", "manager2", "univ1", "admin"]:
        d = step(f"{who} 로그인", *api.call("POST", "/auth/login", {"email": f"{who}@astra.demo", "password": args.password}), 200)
        tok[who] = d["accessToken"]
    C, C2, M1, M2, U1 = tok["company"], tok["company2"], tok["manager1"], tok["manager2"], tok["univ1"]
    day = [0]

    def slot(hours=8):
        day[0] += 1
        start = (datetime.now(KST) + timedelta(days=30 + day[0])).replace(hour=9, minute=0, second=0, microsecond=0)
        return {"startAt": start.isoformat(), "endAt": (start + timedelta(hours=hours)).isoformat()}

    def create(token, equipment_id, title):
        d = step(f"의뢰 생성 '{title}'", *api.call("POST", "/test-requests", {"equipmentId": equipment_id, "title": "[시연] " + title}, token), 201)
        return d["id"]

    def fill_and_submit(token, rid, purpose):
        detail = step("의뢰 상세", *api.call("GET", f"/test-requests/{rid}", token=token), 200)
        content = detail["revision"]["content"]
        content["test"] = {**content.get("test", {}), "testTypeCode": "VIBRATION", "purpose": purpose}
        content["sample"] = {**content.get("sample", {}), "name": "위성 탑재 전장품", "quantity": 1}
        content["conditions"] = [
            {"key": "sample_mass", "label": "시료 질량", "value": 15, "unit": "kg", "status": "CONFIRMED"},
            {"key": "fixture_mass", "label": "고정구 질량", "value": 2, "unit": "kg", "status": "CONFIRMED"},
            {"key": "frequency", "label": "주파수 범위", "min": 20, "max": 2000, "unit": "Hz", "status": "CONFIRMED"}]
        content["accounting"] = {"name": "김회계", "phone": "02-000-0000", "email": "account@example.com"}
        start = (datetime.now(KST) + timedelta(days=40)).date()
        content["schedule"] = {"desiredStartDate": start.isoformat(), "desiredEndDate": (start + timedelta(days=1)).isoformat()}
        step("의뢰서 저장(회계 담당자·이용 희망 기간)", *api.call("PUT", f"/test-requests/{rid}/draft", {"version": detail["version"], "content": content}, token), 200)
        step("제출", *api.call("POST", f"/test-requests/{rid}/submit", None, token), 200)

    def review(token, rid):
        step("검토 시작", *api.call("POST", f"/test-requests/{rid}/review/start", None, token), 200)

    def fee(token, rid, amount, note):
        step(f"예상 이용료 {amount:,}원", *api.call("PUT", f"/test-requests/{rid}/estimated-fee", {"amount": amount, "note": note}, token), 200)

    def additional_info(token, rid):
        d = step("추가 정보 요청(책임자·성적서 종류·시료 처리·청구 방법)", *api.call("POST", f"/test-requests/{rid}/supplements", {"kind": "ADDITIONAL_INFO"}, token), 201)
        return d["supplement"]["id"]

    def answer_info(token, rid, sid):
        content = step("의뢰 상세", *api.call("GET", f"/test-requests/{rid}", token=token), 200)["revision"]["content"]
        content["responsible"] = {"name": "박책임"}
        content["report"] = {"type": "공인성적서(KOLAS)"}
        content["sample"] = {**content.get("sample", {}), "disposal": "시험 후 반환"}
        content["billing"] = {"method": "세금계산서"}
        step("추가 정보 제출(재제출)", *api.call("POST", f"/supplements/{sid}/respond", {"message": "요청하신 정보 입력했습니다.", "content": content, "changeSummary": "책임자·성적서 종류·시료 처리·청구 방법 입력"}, token), 200)

    def accept(token, rid):
        step("검토 통과", *api.call("POST", f"/test-requests/{rid}/accept", None, token), 200)

    def propose(token, rid):
        d = step("일정 제안", *api.call("POST", f"/test-requests/{rid}/reservations", slot(), token), 201)
        return d["reservation"]["id"]

    def confirm(token, resv):
        step("일정 확정", *api.call("POST", f"/reservations/{resv}/confirm", None, token), 200)

    def upload_report(token, rid):
        boundary = "----astra" + uuid.uuid4().hex
        pdf = "%PDF-1.4\n% ASTRA 시연용 성적서\n".encode()
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"category\"\r\n\r\nRESULT_REPORT\r\n"
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"시연 성적서.pdf\"\r\n"
                f"Content-Type: application/pdf\r\n\r\n").encode() + pdf + f"\r\n--{boundary}--\r\n".encode()
        step("성적서 파일 올리기", *api.call("POST", f"/test-requests/{rid}/attachments", raw=body, token=token,
                                       content_type="multipart/form-data; boundary=" + boundary), 201)

    print("\n[1] 작성 중")
    create(C, 48, "진동시험 의뢰서 작성 중")

    print("\n[2] 제출됨 (다온신뢰성시험센터 장비 44)")
    r = create(C, 44, "전장품 진동시험 접수 대기")
    fill_and_submit(C, r, "발사 환경 진동 내구성 확인")

    print("\n[3] 검토 중 + 예상 이용료")
    r = create(C, 48, "위성 전장품 진동시험 검토 중")
    fill_and_submit(C, r, "발사 환경 진동 내구성 확인")
    review(M1, r)
    fee(M1, r, 1_250_000, "진동시험 2일, 고정구 제작비 별도")

    print("\n[4] 추가 정보 요청됨")
    r = create(C, 48, "추가 정보 입력 대기")
    fill_and_submit(C, r, "탑재체 진동 검증")
    review(M1, r)
    additional_info(M1, r)

    print("\n[5] 다시 냄 (추가 정보 제출 → 제출본 2)")
    r = create(C, 48, "추가 정보 제출 완료")
    fill_and_submit(C, r, "탑재체 진동 검증")
    review(M1, r)
    sid = additional_info(M1, r)
    answer_info(C, r, sid)

    print("\n[6] 일정 조율 중 (제안 1건)")
    r = create(C, 48, "시험 일정 조율 중")
    fill_and_submit(C, r, "진동 내구성 확인")
    review(M1, r)
    fee(M1, r, 980_000, "1일 시험 기준")
    accept(M1, r)
    propose(M1, r)

    print("\n[7] 일정 확정")
    r = create(C, 48, "시험 일정 확정")
    fill_and_submit(C, r, "진동 내구성 확인")
    review(M1, r)
    accept(M1, r)
    confirm(M1, propose(C, r))

    print("\n[8] 시험 완료 (결과 + 성적서 파일)")
    r = create(C, 48, "진동시험 완료 · 성적서 발행")
    fill_and_submit(C, r, "발사 환경 진동 내구성 확인")
    review(M1, r)
    fee(M1, r, 1_500_000, "3축 진동시험")
    accept(M1, r)
    confirm(M1, propose(C, r))
    step("결과 등록", *api.call("PUT", f"/test-requests/{r}/result", {
        "summary": "20~2000Hz 정현파 진동시험 3축 완료. 시료 외관·기능 이상 없음",
        "reportNo": "KOLAS-2026-1234", "reportUrl": "https://example.com/reports/KOLAS-2026-1234.pdf"}, M1), 200)
    upload_report(M1, r)
    step("시험 완료", *api.call("POST", f"/test-requests/{r}/complete", None, M1), 200)

    print("\n[9] 반려")
    r = create(C, 48, "시료 규격 초과로 반려")
    fill_and_submit(C, r, "대형 구조물 진동시험")
    review(M1, r)
    step("반려", *api.call("POST", f"/test-requests/{r}/reject", {"reason": "시료 질량이 장비 최대 하중을 넘습니다."}, M1), 200)

    print("\n[10] 조직 간: 대학 → 기업(미르항공정밀) 장비")
    r = create(U1, 86, "대학 연구실 시료 시험 의뢰")
    fill_and_submit(U1, r, "연구 시료 특성 시험")

    print("\n[11] 조직 간: 기업(미르항공정밀) → 대학 장비, 검토 통과")
    r = create(C2, 77, "기업 부품 시험 의뢰")
    fill_and_submit(C2, r, "부품 신뢰성 시험")
    review(U1, r)
    accept(U1, r)

    print("\n[확인] 의뢰 목록·대시보드")
    sent = step("company 보낸 의뢰 목록", *api.call("GET", "/test-requests?box=SENT&size=100", token=C), 200)
    received = step("manager1 받은 의뢰 목록", *api.call("GET", "/test-requests?size=100", token=M1), 200)
    step("manager1 대시보드", *api.call("GET", "/dashboard/summary", token=M1), 200)
    statuses = sorted({x["status"] for x in sent["content"]})
    print(f"\n완료: 단계 {len(STEPS)}개 모두 성공. company 보낸 의뢰 {sent['totalElements']}건 {statuses}, "
          f"manager1 받은 의뢰 {received['totalElements']}건")


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")  # Windows 콘솔에서 한글 깨짐 방지
    t = time.time()
    main()
    print(f"걸린 시간 {time.time() - t:.1f}초")
