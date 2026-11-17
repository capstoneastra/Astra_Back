"""equipment_catalog.json → V8 마이그레이션 SQL 생성.
조직·요금·가용 상태는 가상(시연용), 장비 모델·사양은 제조사 공개자료 기준."""
import hashlib
import io
import json

import os
HERE = os.path.dirname(os.path.abspath(__file__))
S = HERE + '/'
OUT = os.path.join(HERE, '../../../src/main/resources/db/migration/V8__mock_organizations_and_equipment.sql')

HASH = '$2a$10$VEreyAS6ogOggY/RG5UviOv/iWYbRMrLr95dWutc5hK2y6hsJXvci'  # V3 시연 계정과 같은 해시

# (기존 유형, 기존 이름) → (새 유형, 새 이름, 상위 조직명)
RENAMES = [
    ('한국생산기술연구원 진주뿌리기술지원센터', 'INSTITUTION', '한빛뿌리기술지원센터', None),
    ('대전테크노파크 우주·양자산업센터', 'INSTITUTION', '미리내우주환경시험센터', '미리내테크노파크'),
    ('대전테크노파크 국방·로봇·첨단제조센터', 'INSTITUTION', '미리내첨단제조지원센터', '미리내테크노파크'),
    ('한국재료연구원', 'INSTITUTION', '가람재료시험연구원', None),
    ('경상국립대학교 첨단소재분석지원센터', 'UNIVERSITY', '가온대학교 첨단소재분석센터', '가온대학교'),
    ('한국신뢰성기술센터', 'INSTITUTION', '다온신뢰성시험센터', None),
    ('전남대학교 로봇연구소(마이크로의료로봇센터)', 'UNIVERSITY', '새별대학교 로봇연구소', '새별대학교'),
    ('기술지원센터', 'INSTITUTION', '마루기술지원센터', None),
    ('대전테크노파크 반도체·에너지산업센터', 'INSTITUTION', '미리내반도체에너지센터', '미리내테크노파크'),
]

# 새 조직: (유형, 이름, 상위 조직명, 지역, 주소, 전화, 사업자번호, 장비 배분 가중치)
NEW_ORGS = [
    ('INSTITUTION', '온새미로항공시험인증원', None, '사천', '경남 사천시', '055-000-0101', None, 5),
    ('UNIVERSITY', '늘해랑대학교 항공우주공동기기원', '늘해랑대학교', '사천', '경남 사천시', '055-000-0102', None, 5),
    ('UNIVERSITY', '다솜대학교 공동실험실습관', '다솜대학교', '창원', '경남 창원시', '055-000-0103', None, 4),
    ('COMPANY', '미르항공정밀', None, '사천', '경남 사천시', '055-000-0104', '999-00-00001', 4),
    ('COMPANY', '라온스페이스시스템', None, '고흥', '전남 고흥군', '061-000-0105', '999-00-00002', 4),
    ('COMPANY', '도담전자계측', None, '창원', '경남 창원시', '055-000-0106', '999-00-00003', 3),
]

# 진동시험 필수 사양(주파수)이 공개자료에 없는 충격 시험기 4종은 제외
SKIP_MODELS = {'Model 65/81', 'Model 95/115', 'SM-110', 'SM-220'}


def q(v):
    if v is None:
        return 'NULL'
    if isinstance(v, bool):
        return 'TRUE' if v else 'FALSE'
    if isinstance(v, (int, float)):
        return repr(v) if isinstance(v, int) else ('%r' % v)
    return "'" + str(v).replace("'", "''") + "'"


def rnd(key, lo, hi):
    """모델명에서 뽑은 고정 난수 (마이그레이션은 매번 같은 결과여야 함)"""
    h = int(hashlib.md5(key.encode('utf-8')).hexdigest()[:8], 16)
    return lo + h % (hi - lo + 1)


def org_ref(org_type, name):
    return "(SELECT id FROM organizations WHERE org_type=%s AND name=%s)" % (q(org_type), q(name))


def fee(item):
    """(fee_text, fee_amount, fee_unit, duration_text) — 전부 시연용 가상 값"""
    key = item['manufacturer'] + item['model']
    cat, typ = item['category'], item['equipment_type']
    if typ == 'SUPPORT':
        return '협의', None, 'NEGOTIABLE', '협의'
    if cat == '환경시험 장비':
        amt, unit, label, dur = rnd(key + 'f', 6, 30) * 50000, 'DAY', '일', '%d~%d일' % (rnd(key + 'd', 1, 3), rnd(key + 'd', 1, 3) + 2)
    elif cat == '분석장비':
        amt, unit, label, dur = rnd(key + 'f', 5, 30) * 10000, 'SAMPLE', '시료', '%d일' % rnd(key + 'd', 2, 7)
    elif cat == '비파괴검사 장비':
        amt, unit, label, dur = rnd(key + 'f', 4, 16) * 50000, 'CASE', '건', '%d일' % rnd(key + 'd', 1, 5)
    elif typ == 'MANUFACTURING':
        amt, unit, label, dur = rnd(key + 'f', 8, 30) * 10000, 'HOUR', '시간', '%d~%d일' % (rnd(key + 'd', 2, 5), rnd(key + 'd', 2, 5) + 5)
    elif typ == 'MEASUREMENT':
        amt, unit, label, dur = rnd(key + 'f', 3, 12) * 10000, 'HOUR', '시간', '%d일' % rnd(key + 'd', 1, 3)
    else:
        amt, unit, label, dur = rnd(key + 'f', 5, 20) * 10000, 'HOUR', '시간', '%d일' % rnd(key + 'd', 1, 4)
    return '{:,}원/{}'.format(amt, label), amt, unit, dur


def equipment_type(item):
    """시험·측정 장비는 시험유형에 연결돼야 함 (DatabaseSchemaTest). 맞는 시험유형이 없는
    질량특성 측정기·초음파 탐상기는 지원 장비(SUPPORT)로 분류"""
    if not item['test_types'] and item['equipment_type'] in ('TEST', 'MEASUREMENT'):
        return 'SUPPORT'
    return item['equipment_type']


def availability(key):
    r = rnd(key + 'a', 1, 100)
    if r <= 68:
        return 'AVAILABLE'
    if r <= 80:
        return 'BUSY'
    if r <= 86:
        return 'MAINTENANCE'
    if r <= 88:
        return 'UNAVAILABLE'
    return 'UNKNOWN'


def access_scope(key):
    r = rnd(key + 's', 1, 100)
    return 'EXTERNAL_ALLOWED' if r <= 86 else ('INTERNAL_ONLY' if r <= 91 else 'UNKNOWN')


def operation_mode(key):
    r = rnd(key + 'o', 1, 100)
    return 'INSTITUTION_OPERATED' if r <= 60 else ('SUPERVISED_SELF_SERVICE' if r <= 85 else 'SELF_SERVICE')


catalog = [x for x in json.load(io.open(S + 'equipment_catalog.json', encoding='utf-8')) if x['model'] not in SKIP_MODELS]

# 가중치대로 조직 순서를 돌며 배분 (같은 종류 장비가 여러 조직에 고르게 퍼지도록 카탈로그 순서 그대로)
slots = []
for o in NEW_ORGS:
    slots += [o] * o[7]
out = []
w = out.append

w("""-- =====================================================================
-- V8: 조직을 가상 조직으로 바꾸고 기업·기관·대학 공유 플랫폼용 시연 장비를 추가
--  * 기존 기관 9곳의 이름을 가상 이름으로 바꿈 (id 는 그대로라 기존 장비·의뢰·계정 연결 유지).
--    대학 부설 2곳은 유형을 UNIVERSITY 로 변경
--  * 새 가상 조직 6곳 (기관 1, 대학 2, 기업 3) + 시연 계정 2개
--  * 새 장비 %d대: 제조사·모델명·사양은 제조사 공개자료 기준의 실제 제품,
--    보유 조직·요금·가동률·가용 상태·예상 비용은 전부 가상 → is_mock=TRUE
--    (출처에서 확인한 사양은 REAL, 추정치는 MOCK/DEMO_ONLY 로 구분)
--  * 기존 장비(ASTRA-CAND-xxxx)는 가상 조직 소속이 되므로 is_mock=TRUE 로 두고
--    실제 기관 자료를 가리키던 출처(source_url·source_file·source_page)를 비움
--  * 이 파일은 docs/design/tools/gen_v8.py 가 equipment_catalog.json 으로 생성 (난수는 모델명 해시라 매번 같은 결과).
--    이미 적용된 마이그레이션이므로 다시 생성해서 덮어쓰지 말 것 (Flyway 체크섬)
-- =====================================================================
""" % len(catalog))

w('-- 1. 기존 조직 → 가상 이름')
for old, typ, new, parent in RENAMES:
    w("UPDATE organizations SET org_type=%s, name=%s, parent_name=%s WHERE org_type='INSTITUTION' AND name=%s;"
      % (q(typ), q(new), q(parent), q(old)))

w('\n-- 2. 기존 장비: 가상 조직 소속이므로 시연 데이터로 표시하고 실제 기관 출처를 비움')
w("UPDATE equipment SET is_mock=TRUE, source_url=NULL, source_file=NULL, source_page=NULL WHERE external_id LIKE 'ASTRA-CAND-%';")

w('\n-- 3. 새 가상 조직 (승인 상태)')
for typ, name, parent, region, address, phone, brn, _ in NEW_ORGS:
    w("INSERT INTO organizations (org_type, name, parent_name, business_reg_no, region, address, phone, is_verified) "
      "VALUES (%s, %s, %s, %s, %s, %s, %s, TRUE);" % (q(typ), q(name), q(parent), q(brn), q(region), q(address), q(phone)))

w('\n-- 4. 시연 계정 추가 (비밀번호는 V3 시연 계정과 같음): 대학 담당자, 장비를 보유한 기업 회원')
w("INSERT INTO users (organization_id, email, password_hash, name, phone, role, status, approved_by, approved_at)\n"
  "SELECT o.id, 'univ1@astra.demo', %s, '최대학', '055-000-0004', 'INSTITUTION_MANAGER', 'ACTIVE', a.id, now()\n"
  "FROM organizations o, users a WHERE o.org_type='UNIVERSITY' AND o.name='늘해랑대학교 항공우주공동기기원' AND a.email='admin@astra.demo';" % q(HASH))
w("INSERT INTO users (organization_id, email, password_hash, name, phone, role, status, approved_by, approved_at)\n"
  "SELECT o.id, 'company2@astra.demo', %s, '정기업', '055-000-0005', 'COMPANY_USER', 'ACTIVE', a.id, now()\n"
  "FROM organizations o, users a WHERE o.org_type='COMPANY' AND o.name='미르항공정밀' AND a.email='admin@astra.demo';" % q(HASH))

w('\n-- 5. 시연 장비 (ASTRA-DEMO-xxxx)')
NOTE = 'DEMO: 시연용 가용 상태. 실제 이용 전 보유 조직 확인 필요.'
counts = {}
for i, item in enumerate(catalog):
    org = slots[i % len(slots)]
    counts[org[1]] = counts.get(org[1], 0) + 1
    ext = 'ASTRA-DEMO-%04d' % (i + 1)
    key = item['manufacturer'] + item['model']
    fee_text, fee_amount, fee_unit, duration = fee(item)
    avail = availability(key)
    desc = '%s %s. 시연용 데이터: 모델·사양은 제조사 공개자료 기준이고 보유 조직·요금·가용 상태는 가상입니다.' % (
        item['manufacturer'], item['model'])
    w("INSERT INTO equipment (external_id, institution_id, name, model, manufacturer, category, equipment_type, region, "
      "description, access_scope, fee_text, fee_amount, fee_unit, duration_text, utilization_rate, availability, "
      "availability_updated_at, availability_note, lead_time_days, operation_mode, source_type, source_url, "
      "verification_status, spec_verification_status, is_mock) VALUES (%s);" % ', '.join([
          q(ext), org_ref(org[0], org[1]), q(item['name_ko']), q(item['model']), q(item['manufacturer']),
          q(item['category']), q(equipment_type(item)), q(org[3]), q(desc), q(access_scope(key)), q(fee_text),
          q(fee_amount), q(fee_unit), q(duration), q(rnd(key + 'u', 20, 90)), q(avail),
          'NULL' if avail == 'UNKNOWN' else 'now()', 'NULL' if avail == 'UNKNOWN' else q(NOTE),
          q(rnd(key + 'l', 3, 21)), q(operation_mode(key)), q('MANUAL'), q(item['source_url']),
          q('LISTING_CONFIRMED'), q('NOT_VERIFIED'), 'TRUE']))
    eq = "(SELECT id FROM equipment WHERE external_id=%s)" % q(ext)
    for s in item['specs']:
        real = bool(s['sourced'])
        w("INSERT INTO equipment_specs (equipment_id, spec_key, label, value_num, min_num, max_num, unit, original_unit, "
          "source_type, verification_status, usage_scope) VALUES (%s);" % ', '.join([
              eq, q(s['spec_key']), q(s['label_ko']), q(s['value']), q(s['min']), q(s['max']), q(s['unit']), q(s['unit']),
              q('REAL' if real else 'MOCK'), q('NOT_VERIFIED'), q('GENERAL' if real else 'DEMO_ONLY')]))
    for code in item['test_types']:
        base = fee_amount or 300000
        mult = {'HOUR': 8, 'DAY': 1, 'CASE': 1, 'SAMPLE': 3}.get(fee_unit, 1)
        lo = base * mult
        w("INSERT INTO equipment_test_types (equipment_id, test_type_code, est_cost_min, est_cost_max, est_duration_days, "
          "note) VALUES (%s, %s, %d, %d, %d, %s);" % (eq, q(code), lo, lo * rnd(key + code, 2, 3),
                                                       rnd(key + code + 'd', 1, 5), q('시연용 예상 비용·기간')))

io.open(OUT, 'w', encoding='utf-8', newline='\n').write('\n'.join(out) + '\n')
print('equipment', len(catalog), 'lines', len(out))
for k, v in counts.items():
    print(v, k)
# 검증: 길이 제한
for item in catalog:
    assert len(item['name_ko']) <= 200 and len(item['model']) <= 200 and len(item['category']) <= 100
    assert len(item['source_url']) <= 1000
    for s in item['specs']:
        assert len(s['label_ko']) <= 100
        assert s['value_text'] is None
        assert (s['value'] is not None) != (s['min'] is not None and s['max'] is not None), item['model']
keys = [(x['manufacturer'], x['model']) for x in catalog]
assert len(keys) == len(set(keys))
