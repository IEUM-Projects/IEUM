// 현재 위치 → 가장 가까운 시·군·구 (POL-01, LOC-02)
// 휴대폰·PC의 위치(GPS 좌표)는 이 브라우저 안에서만 쓰고 이음 서버로 보내지 않는다.
// 위치정보를 서버로 보내 쓰면 위치기반서비스사업 신고 대상이 될 수 있어서 이렇게 한다.
//
// LOC-02: 서버에는 여전히 '시·군·구 중심 + 넉넉한 반경'만 보내고,
//         받은 가게·시장·축제를 기기 안에서 실제 내 위치로 다시 재고, 고르고, 가까운 순으로 정렬한다.
//         그래서 구 가장자리에 있거나 옆 구가 잡혀도 '내 주변'이 정확해진다.
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
      (pos) => resolve({ lat: pos.coords.latitude, lng: pos.coords.longitude, accuracy: pos.coords.accuracy }),
      () => reject(new Error('위치 권한이 없어요.')),
      // 정밀 위치(GPS)를 쓰고, 1분 안에 받은 위치는 다시 쓴다
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 60000, ...options },
    );
  });
}

/** 두 지점 사이 거리 (m) */
export function meters(a, b) {
  const rad = Math.PI / 180;
  const h = Math.sin(((b.lat - a.lat) * rad) / 2) ** 2
    + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(((b.lng - a.lng) * rad) / 2) ** 2;
  return 12742000 * Math.asin(Math.min(1, Math.sqrt(h)));
}

/**
 * 지금 위치에서 가장 가까운 시·군·구.
 * { code, sidoName, name, label, lat, lng, here }
 *  - lat·lng : 그 지역의 중심 (서버로 보내도 되는 값)
 *  - here    : 실제 내 위치 {lat, lng, accuracy}. 기기 안에서만 쓴다. 서버 요청·주소(URL)·저장소에 넣지 않는다
 * 위치를 못 얻거나 지역 목록을 못 받으면 Error (message는 화면에 그대로 보여도 됨)
 */
export async function nearestRegion(options = {}) {
  const [here, list] = await Promise.all([position(options), regions().catch(() => {
    throw new Error('지역 목록을 불러오지 못했어요.');
  })]);
  let best = null;
  list.forEach((sido) => (sido.sigungus ?? []).forEach((g) => {
    if (g.centerLat == null || g.centerLng == null) return;
    const d = meters(here, { lat: g.centerLat, lng: g.centerLng });
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
    here,
  };
}

const STEP = 2000;   // 반경을 2km 단위로만 넓힌다 (서버가 받는 반경으로 실제 위치를 좁혀 알 수 없게)

/**
 * 서버에 보낼 조건: 시·군·구 중심 + (원하는 반경 + 내 위치까지 거리를 2km 단위로 올린 만큼).
 * here 가 없으면(지역을 직접 고른 경우) 그 지점 그대로.
 * @param {{lat:number, lng:number, here?:{lat:number,lng:number}}} place
 */
export function queryAround(place, radius) {
  if (!place.here) return { lat: place.lat.toFixed(6), lng: place.lng.toFixed(6), radius };
  const off = meters(place.here, place);
  return { lat: place.lat.toFixed(6), lng: place.lng.toFixed(6), radius: radius + Math.max(STEP, Math.ceil(off / STEP) * STEP) };
}

/**
 * 서버가 준 목록을 실제 내 위치(here)로 다시 재서, radius 안의 것만 가까운 순으로 (distanceM 도 고쳐 둔다).
 * here 가 없으면 그대로 돌려준다.
 * order: 정렬 기준 (기본은 가까운 순. 축제처럼 서버가 다른 순서로 주는 것은 같은 순서를 넘겨 준다)
 * @param {Array<{latitude:number|string, longitude:number|string, distanceM?:number}>} items
 */
export const BY_DISTANCE = (a, b) => a.distanceM - b.distanceM;

export function nearHere(items, place, radius, order = BY_DISTANCE) {
  if (!place.here) return items;
  return items
    .map((it) => ({ ...it, distanceM: Math.round(meters(place.here, { lat: Number(it.latitude), lng: Number(it.longitude) })) }))
    .filter((it) => Number.isFinite(it.distanceM) && it.distanceM <= radius)
    .sort(order);
}
