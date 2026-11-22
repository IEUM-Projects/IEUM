// 테마로 걷기 공통 (THM-01)
export const THEME_ICONS = {
  alleys: ['M3 21h18', 'M5 21V9l4-3 4 3v12', 'M13 21V12l3-2 3 2v9', 'M8 13h2M8 17h2M15 15h2'],
  'market-stores': ['M3 10l2-6h14l2 6', 'M3 10a3 3 0 0 0 6 0 3 3 0 0 0 6 0 3 3 0 0 0 6 0', 'M5 13v7h14v-7', 'M10 20v-4h4v4'],
  'fair-markets': ['M3.5 5h17v15h-17z', 'M3.5 10h17M8 3v4M16 3v4', 'M8 14h2M14 14h2M8 17h2'],
};

/** 기준 위치: 주소의 ?lat&lng&label (홈에서 넘어온 위치) → 홈에서 고른 지역 → 없음(null) */
export function homePlace() {
  const q = new URLSearchParams(location.search);
  const lat = Number(q.get('lat'));
  const lng = Number(q.get('lng'));
  if (q.has('lat') && q.has('lng') && lat >= 33 && lat <= 39 && lng >= 124 && lng <= 132) {
    return { lat, lng, label: (q.get('label') || '고른 위치').slice(0, 30) };
  }
  try {
    const p = JSON.parse(localStorage.getItem('ieum_home_place'));
    return p && typeof p.lat === 'number' && typeof p.lng === 'number' ? p : null;
  } catch (_) {
    return null;
  }
}

/** API에 넘길 좌표 */
export function placeQuery(place) {
  return place ? `lat=${place.lat.toFixed(6)}&lng=${place.lng.toFixed(6)}` : '';
}

/** 다른 테마 화면으로 넘길 위치 (이름 포함) */
export function placeLinkQuery(place) {
  return place ? new URLSearchParams({ lat: place.lat.toFixed(6), lng: place.lng.toFixed(6), label: place.label }).toString() : '';
}

export function placeText(place) {
  return place.label === '내 주변' ? '내 주변' : `${place.label} 기준`;
}

export function svgIcon(paths, cls = '') {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  if (cls) svg.setAttribute('class', cls);
  paths.forEach((d) => {
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', d);
    svg.append(path);
  });
  return svg;
}

/** 가까운 반경 글: 2km 안 3곳 / 30km 안에는 없음 (THM-02: 2km → 10km → 30km 중 처음 있는 반경) */
export function nearText(t) {
  if (t.near === null || t.near === undefined) return null;
  const r = distText(t.nearRadius ?? 30000);
  return t.near > 0 ? `${r} 안 ${t.near.toLocaleString()}${t.unit}` : `${r} 안에는 없음`;
}

/** 걸어서 걸리는 시간(분): 골목을 돌아가는 만큼 직선의 1.3배, 분당 약 67m (course-plan.js 와 같음) */
export function walkMinutes(m) {
  return Math.max(1, Math.round((m * 1.3) / 67));
}

/** 목록 오른쪽 거리 글: 2km 안이면 걷는 시간까지 '1.2km · 걸어서 약 23분' */
export function moveText(m) {
  if (m === null || m === undefined) return '';
  return m <= 2000 ? `${distText(m)} · 걸어서 약 ${walkMinutes(m)}분` : `여기서 ${distText(m)}`;
}

/** 테마 요약 한 줄: '서울 중구 기준 2km 안 3곳 · 전국 46곳' */
export function countText(t, place) {
  const parts = [];
  if (place && nearText(t)) parts.push(`${placeText(place)} ${nearText(t)}`);
  parts.push(`전국 ${t.total.toLocaleString()}${t.unit}`);
  if (t.today !== null && t.today !== undefined) parts.push(`오늘 장 서는 곳 ${t.today.toLocaleString()}${t.unit}`);
  return parts.join(' · ');
}

/** 1234 → '1.2km', 300 → '300m' */
export function distText(m) {
  if (m === null || m === undefined) return '';
  return m < 1000 ? `${m}m` : `${(m / 1000).toFixed(m < 10000 ? 1 : 0).replace(/\.0$/, '')}km`;   // 2000 → '2km'
}
