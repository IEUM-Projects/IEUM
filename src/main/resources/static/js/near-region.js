// 현재 위치 → 가장 가까운 시·군·구 (POL-01)
// 휴대폰·PC의 위치(GPS 좌표)는 이 브라우저 안에서만 쓰고 이음 서버로 보내지 않는다.
// 서버에는 고른 시·군·구의 중심 좌표만 간다 (지역을 직접 고른 것과 같음).
// 위치정보를 서버로 보내 쓰면 위치기반서비스사업 신고 대상이 될 수 있어서 이렇게 한다.
import { api } from './api.js';

let regionsPromise = null;

function regions() {
  regionsPromise ??= api.regions().catch((e) => { regionsPromise = null; throw e; });
  return regionsPromise;
}

function position(options) {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error('이 브라우저는 위치 기능을 지원하지 않아요.'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => resolve({ lat: pos.coords.latitude, lng: pos.coords.longitude }),
      () => reject(new Error('위치 권한이 없어요.')),
      { enableHighAccuracy: false, timeout: 8000, maximumAge: 300000, ...options },
    );
  });
}

/** 두 지점 사이 거리 (km) */
function km(a, b) {
  const rad = Math.PI / 180;
  const h = Math.sin(((b.lat - a.lat) * rad) / 2) ** 2
    + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(((b.lng - a.lng) * rad) / 2) ** 2;
  return 12742 * Math.asin(Math.min(1, Math.sqrt(h)));
}

/**
 * 지금 위치에서 가장 가까운 시·군·구. { code, sidoName, name, label, lat, lng } (lat·lng는 그 지역의 중심)
 * 위치를 못 얻거나 지역 목록을 못 받으면 Error (message는 화면에 그대로 보여도 됨)
 */
export async function nearestRegion(options = {}) {
  const [here, list] = await Promise.all([position(options), regions().catch(() => {
    throw new Error('지역 목록을 불러오지 못했어요.');
  })]);
  let best = null;
  list.forEach((sido) => (sido.sigungus ?? []).forEach((g) => {
    if (g.centerLat == null || g.centerLng == null) return;
    const d = km(here, { lat: g.centerLat, lng: g.centerLng });
    if (!best || d < best.d) best = { d, sido, g };
  }));
  if (!best) throw new Error('가까운 지역을 찾지 못했어요.');
  return {
    code: best.g.code,
    sidoName: best.sido.sidoName,
    name: best.g.name,
    label: `${best.sido.sidoName} ${best.g.name}`,
    lat: best.g.centerLat,
    lng: best.g.centerLng,
  };
}
