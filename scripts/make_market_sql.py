"""전국전통시장표준데이터 CSV → 08_data_markets.sql
사용법 (1년에 한 번 새 CSV가 나오면):
    python scripts/make_market_sql.py 전국전통시장표준데이터.csv sql/08_data_markets.sql
  CSV는 공공데이터포털에서 받은 그대로(cp949) 쓰면 된다. 표준 라이브러리만 사용 (설치할 것 없음)
"""
import csv, re, sys
from collections import Counter

src, out = sys.argv[1], sys.argv[2]
rows = list(csv.DictReader(open(src, encoding='cp949')))
SOURCE = '소상공인시장진흥공단 전국전통시장표준데이터'
stats = Counter()


def clean(v):
    v = (v or '').replace(' ', ' ').strip()
    return v or None


def q(v):
    if v is None:
        return 'NULL'
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + v.replace('\\', '\\\\').replace("'", "''") + "'"


def homepage(v):
    s = clean(v)
    if not s:
        return None
    s = re.sub(r'^(https?):/*(?=[^/])', r'\1://', s, flags=re.I)        # http:abc → http://abc
    if not re.match(r'(?i)^https?://', s):
        s = 'http://' + s
    if not re.match(r'(?i)^https?://[a-z0-9-]+(\.[a-z0-9-]+)+(:\d+)?(/\S*)?$', s):
        stats['homepage_dropped'] += 1
        return None
    stats['homepage'] += 1
    return s


def market_days(cycle):
    if cycle == '매일':
        return None
    digits = [int(d) % 10 for d in re.findall(r'(\d+)\s*일', cycle)]
    if not digits:
        raise ValueError('unknown cycle: ' + cycle)
    return ','.join(str(d) for d in sorted(set(digits), key=lambda d: (d == 0, d)))


def coord(v, lo, hi):
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return round(f, 7) if lo <= f <= hi else None


values = []
keys = set()
for r in rows:
    name = clean(r['시장명'])
    road, jibun = clean(r['소재지도로명주소']), clean(r['소재지지번주소'])
    key = f"{name}|{road or jibun}"
    if key in keys:
        stats['duplicate_skipped'] += 1
        continue
    keys.add(key)
    mtype, cycle = clean(r['시장유형']), clean(r['시장개설주기'])
    lat, lng = coord(r['위도'], 33, 39), coord(r['경도'], 124, 132)
    if lat is None or lng is None:
        lat = lng = None
        stats['no_coord'] += 1
    count = clean(r['점포수'])
    year = clean(r['개설연도'])
    year = int(year) if year and year.isdigit() and 1800 <= int(year) <= 2100 else None
    goods = clean(r['취급품목'])
    values.append('(' + ', '.join([
        q(key), q(name), q(mtype), q(1 if '상설장' in mtype else 0), q(cycle), q(market_days(cycle)),
        q(road), q(jibun), q(lat), q(lng), q('SOURCE' if lat is not None else 'NONE'),
        q(int(count) if count and count.isdigit() else None), q(goods[:300] if goods else None),
        q(homepage(r['홈페이지주소'])), q(1 if clean(r['공중화장실보유여부']) == 'Y' else 0),
        q(1 if clean(r['주차장보유여부']) == 'Y' else 0), q(year), q(SOURCE), q(clean(r['데이터기준일자'])),
    ]) + ')')

COLS = ('source_key, name, market_type, is_permanent, cycle_raw, market_days, road_address, jibun_address, '
        'latitude, longitude, geocode_status, store_count, goods, homepage_url, has_toilet, has_parking, '
        'opened_year, source, data_date')
# 새 버전 데이터로 다시 실행하면 내용을 최신으로 바꾼다.
# 단, 원본에 좌표가 없을 때는 서버가 카카오로 찾아 둔 좌표·지역을 지우지 않는다.
UPDATE = '''ON DUPLICATE KEY UPDATE
    name = new.name, market_type = new.market_type, is_permanent = new.is_permanent, cycle_raw = new.cycle_raw,
    market_days = new.market_days, road_address = new.road_address, jibun_address = new.jibun_address,
    sido_code = IF(new.latitude IS NULL OR (new.latitude = markets.latitude AND new.longitude = markets.longitude), markets.sido_code, NULL),
    sigungu_code = IF(new.latitude IS NULL OR (new.latitude = markets.latitude AND new.longitude = markets.longitude), markets.sigungu_code, NULL),
    geocode_status = IF(new.latitude IS NULL, markets.geocode_status, 'SOURCE'),
    latitude = COALESCE(new.latitude, markets.latitude), longitude = COALESCE(new.longitude, markets.longitude),
    store_count = new.store_count, goods = new.goods, homepage_url = new.homepage_url, has_toilet = new.has_toilet,
    has_parking = new.has_parking, opened_year = new.opened_year, source = new.source, data_date = new.data_date;'''

with open(out, 'w', encoding='utf-8') as f:
    f.write(f'''-- 08. 전통시장 {len(values):,}곳 (소상공인시장진흥공단 전국전통시장표준데이터, 기준일 {rows[0]['데이터기준일자']})
-- 07_schema_markets.sql 다음에 실행
-- 여러 번 실행해도 안전하다: 같은 시장(시장명+주소)은 새로 넣지 않고 내용만 최신으로 바꾼다
-- 좌표가 없는 {stats['no_coord']}곳과 모든 시장의 지역 코드는 서버를 켤 때 카카오 주소 검색으로 채운다
USE ieum;

''')
    for i in range(0, len(values), 200):
        f.write(f'INSERT INTO markets ({COLS}) VALUES\n')
        f.write(',\n'.join(values[i:i + 200]))
        f.write('\nAS new\n' + UPDATE + '\n\n')
print(len(values), dict(stats))
