// 걷기 코스 계산 (TOUR-01): 화면과 시험이 같이 쓴다

/** 두 점 사이 직선 거리(m) */
export function distanceM(a, b) {
  const R = 6371008.8;
  const rad = (d) => (d * Math.PI) / 180;
  const p1 = rad(a.latitude);
  const p2 = rad(b.latitude);
  const h = Math.sin((p2 - p1) / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(rad(b.longitude - a.longitude) / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** 걷는 시간(분): 골목을 돌아가는 만큼 직선의 1.3배, 시속 4km(분당 약 67m) */
export function walkMinutes(m) {
  return Math.max(1, Math.round((m * 1.3) / 67));
}

/** 출발점에서 가장 가까운 곳부터 차례로 (다음은 지금 자리에서 가장 가까운 곳) */
export function orderStops(start, stops) {
  const left = [...stops];
  const ordered = [];
  let here = start;
  while (left.length) {
    let best = 0;
    for (let i = 1; i < left.length; i++) {
      if (distanceM(here, left[i]) < distanceM(here, left[best])) best = i;
    }
    const next = left.splice(best, 1)[0];
    ordered.push({ ...next, legM: Math.round(distanceM(here, next)) });
    here = next;
  }
  return ordered;
}

/** 처음에 넣을 곳: 가까운 음식점 하나, 관광지 하나(없으면 레포츠), 문화시설 하나, 카페·간식 하나 (있는 것만) */
export function defaultPicks(stores, spots) {
  const first = (list, test) => list.filter(test).sort((a, b) => a.distanceM - b.distanceM)[0];
  return [
    first(stores, (s) => s.category === '음식점'),
    first(spots, (s) => s.type === 12) ?? first(spots, (s) => s.type === 28),
    first(spots, (s) => s.type === 14),
    first(stores, (s) => s.category === '카페·간식'),
  ].filter(Boolean);
}

/** 코스 점들의 열쇠 (출발점 → 들르는 곳 순서). 도보 경로를 이미 받은 순서인지 확인할 때 쓴다 */
export function walkKey(start, stops) {
  return [start, ...stops].map((p) => `${Number(p.latitude).toFixed(6)},${Number(p.longitude).toFixed(6)}`).join('|');
}

/** 구간 하나: TMAP 도보 경로를 받았으면 그 시간·거리, 못 받았으면 직선 어림 */
export function legInfo(stop, leg) {
  if (leg?.routed) return { minutes: Math.max(1, Math.round(leg.seconds / 60)), meters: leg.distanceM, routed: true };
  return { minutes: walkMinutes(stop.legM), meters: stop.legM, routed: false };
}
