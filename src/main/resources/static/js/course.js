// 걷기 코스 (TOUR-01): 시장(또는 고른 위치)에서 시작해 백년가게·관광지·문화시설·레포츠·여행코스를 가까운 순으로 잇는다
import { formatDistance, directionsUrl, loadKakaoMapSdk } from './api.js';
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { photoCredit } from './festival-format.js';
import { defaultPicks, legInfo, orderStops, walkKey } from './course-plan.js';

const $ = (id) => document.getElementById(id);
const MAX_STOPS = 6;
const KIND_LABEL = { store: '백년가게', creator: '로컬크리에이터', 12: '관광지', 14: '문화시설', 28: '레포츠', 25: '여행코스' };
const state = { start: null, stores: [], spots: [], chosen: [], tab: 'store', tourAvailable: true, storeRadiusM: 1500, spotRadiusM: 2000, walk: { key: '', legs: null, enabled: false }, tourSource: '' };
let walkPending = '';
let kakao = null;
let map = null;
let drawn = [];

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([key, value]) => {
    if (value === null || value === undefined || value === false) return;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value === true ? '' : value);
  });
  children.flat().filter((c) => c !== null && c !== undefined && c !== false && c !== '').forEach((c) => node.append(c));
  return node;
}

function toast(message) {
  $('toast').textContent = message;
  $('toast').hidden = false;
  clearTimeout(toast.t);
  toast.t = setTimeout(() => { $('toast').hidden = true; }, 4000);
}

/** 가게와 관광지를 같은 모양으로 */
const fromStore = (s) => ({ key: `s${s.id}`, kind: s.type === 'CREATOR' ? 'creator' : 'store', id: s.id, name: s.name, category: s.category, sub: s.category ?? '분류 없음', latitude: s.latitude, longitude: s.longitude, distanceM: s.distanceM, onnuri: s.onnuri });
const fromSpot = (t) => ({ key: `t${t.id}`, kind: String(t.type), type: t.type, id: t.id, name: t.title, sub: t.typeName, address: t.address, latitude: t.latitude, longitude: t.longitude, distanceM: t.distanceM, imageUrl: t.imageUrl, imageLicense: t.imageLicense });

function plan() {
  return orderStops(state.start, state.chosen);
}

// ---------------------------------------------------------------- 그리기

function numberPin(n, color) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="30" height="40" viewBox="0 0 30 40"><path d="M15 39C15 39 2 23 2 14a13 13 0 0 1 26 0c0 9-13 25-13 25z" fill="${color}" stroke="#fff" stroke-width="2"/><text x="15" y="19" font-size="13" font-family="sans-serif" font-weight="700" text-anchor="middle" fill="#fff">${n}</text></svg>`;
  return new kakao.maps.MarkerImage(`data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`, new kakao.maps.Size(30, 40), { offset: new kakao.maps.Point(15, 39) });
}

/** 지금 순서에 맞는 도보 경로 (아직 없거나 순서가 바뀌었으면 null) */
function currentLegs(stops = plan()) {
  return stops.length && state.walk.key === walkKey(state.start, stops) ? state.walk.legs : null;
}

function drawMap(stops, legs = currentLegs(stops)) {
  if (!map) return;
  drawn.forEach((o) => o.setMap(null));
  const pts = [state.start, ...stops];
  const path = pts.map((p) => new kakao.maps.LatLng(p.latitude, p.longitude));
  drawn = pts.map((p, i) => new kakao.maps.Marker({ map, position: path[i], title: i === 0 ? `출발: ${p.name}` : `${i}. ${p.name}`, image: numberPin(i === 0 ? '출' : i, i === 0 ? '#11473d' : '#ae620b') }));
  const all = [...path];
  if (kakao.maps.Polyline && stops.length) {
    // 도보 경로를 받은 구간은 실제 길을 따라 실선, 못 받은 구간은 직선 점선
    stops.forEach((s, i) => {
      const leg = legs?.[i];
      const line = leg?.routed ? leg.path.map(([lat, lng]) => new kakao.maps.LatLng(lat, lng)) : [path[i], path[i + 1]];
      if (leg?.routed) all.push(...line);
      drawn.push(new kakao.maps.Polyline({ map, path: line, strokeWeight: leg?.routed ? 5 : 4, strokeColor: '#1b7a66', strokeOpacity: 0.85, strokeStyle: leg?.routed ? 'solid' : 'shortdash' }));
    });
  }
  if (kakao.maps.LatLngBounds && pts.length > 1) {
    const bounds = new kakao.maps.LatLngBounds();
    all.forEach((p) => bounds.extend(p));
    map.setBounds(bounds, 40, 40, 40, 40);
  }
}

function legText(s, prev, leg) {
  const info = legInfo(s, leg);
  return info.routed
    ? `${prev.name}에서 걸어서 약 ${info.minutes}분 · ${formatDistance(info.meters)} (도보 경로)`
    : `${prev.name}에서 걸어서 약 ${info.minutes}분 (직선 ${formatDistance(info.meters)})`;
}

function summaryText(stops, legs) {
  if (!stops.length) return '들르는 곳을 넣으면 거리와 시간을 계산해 드려요.';
  const infos = stops.map((s, i) => legInfo(s, legs?.[i]));
  const minutes = infos.reduce((n, x) => n + x.minutes, 0);
  const meters = infos.reduce((n, x) => n + x.meters, 0);
  const routed = infos.filter((x) => x.routed).length;
  const how = routed === infos.length ? `도보 경로 ${formatDistance(meters)}`
    : routed ? `${formatDistance(meters)}, 일부는 직선 어림` : `직선 ${formatDistance(meters)}`;
  return `${stops.length}곳 · 걸어서 약 ${minutes}분 (${how}) · 가까운 순`;
}

function noteText() {
  const walk = state.walk.enabled
    ? `걷는 시간과 거리는 TMAP 도보 경로 기준이에요 (경로를 받지 못한 구간은 직선 거리로 어림). `
    : `시간은 직선 거리로 어림한 값이에요. `;
  return walk + `길 안내는 각 장소의 '길찾기'(카카오맵)에서 '도보'로 확인해 주세요. `
    + `관광지·문화시설·레포츠·여행코스: ${state.tourSource} (사진은 공공누리 출처 표시).`
    + (state.tourAvailable ? '' : ' 지금은 관광지 정보를 불러오지 못해서 백년가게만 보여 드려요.');
}

/** 도보 경로를 받아 오면 구간 문구·합계·지도 선만 바꾼다 (목록을 다시 그리지 않아 초점이 그대로) */
function applyWalk() {
  const stops = plan();
  const legs = currentLegs(stops);
  if (!legs) return;
  const prevs = [state.start, ...stops];
  document.querySelectorAll('#stops .stop-leg').forEach((p, i) => { if (stops[i]) p.textContent = legText(stops[i], prevs[i], legs[i]); });
  $('summary').textContent = summaryText(stops, legs);
  $('note').textContent = noteText();
  drawMap(stops, legs);
}

async function requestWalk(stops) {
  if (!stops.length) return;
  const key = walkKey(state.start, stops);
  if (state.walk.key === key || walkPending === key) return;
  walkPending = key;
  try {
    const query = key.split('|').map((p) => `p=${encodeURIComponent(p)}`).join('&');
    const res = await fetch(`/api/tour/walk?${query}`);
    if (!res.ok) throw new Error();
    const data = await res.json();
    state.walk = { key, legs: data.legs, enabled: data.enabled };
    if (walkKey(state.start, plan()) === key) applyWalk();
  } catch (_) {
    // 도보 경로를 못 받으면 직선 어림값을 그대로 둔다
  } finally {
    if (walkPending === key) walkPending = '';
  }
}

function stopItem(s, i, prev, leg) {
  const remove = el('button', { type: 'button', class: 'button-text', 'aria-label': `${s.name} 빼기`, text: '빼기' });
  remove.addEventListener('click', () => {
    state.chosen = state.chosen.filter((c) => c.key !== s.key);
    render();
    toast(`${s.name}을(를) 뺐어요.`);
    ($('stops').querySelector('button') ?? $('candidates').querySelector('button'))?.focus();
  });
  const title = s.kind === 'store' || s.kind === 'creator'
    ? el('a', { href: `/store.html?id=${s.id}`, text: s.name })
    : el('span', { text: s.name });
  return el('li', { class: 'stop', 'data-key': s.key },
    el('span', { class: 'stop-no', 'aria-hidden': 'true', text: String(i + 1) }),
    el('div', { class: 'stop-body' },
      el('p', { class: 'stop-leg', text: legText(s, prev, leg) }),
      el('h3', { class: 'stop-name' }, el('span', { class: 'sr-only', text: `${i + 1}번째. ` }), title),
      el('p', { class: 'stop-meta' }, el('span', { class: `kind kind-${s.kind}`, text: KIND_LABEL[s.kind] }), `${s.kind === 'store' || s.kind === 'creator' ? ` ${s.sub}` : ''}${s.onnuri ? ' · 온누리상품권' : ''}${s.address ? ` · ${s.address}` : ''}`),
      s.imageUrl ? el('figure', { class: 'stop-photo' },
        el('img', { src: s.imageUrl, alt: '', loading: 'lazy' }),
        el('figcaption', { text: photoCredit(s.imageLicense) })) : null,
      el('div', { class: 'stop-actions' },
        el('a', { class: 'button-text', href: directionsUrl(s), target: '_blank', rel: 'noopener', 'aria-label': `${s.name} 길찾기 (카카오맵, 새 창)`, text: '길찾기' }),
        remove)));
}

function candidateItem(c) {
  const added = state.chosen.some((x) => x.key === c.key);
  const add = el('button', { type: 'button', class: 'button-secondary small', 'aria-label': `${c.name} 코스에 넣기`, text: added ? '들어 있음' : '넣기' });
  if (added) add.disabled = true;
  add.addEventListener('click', () => {
    if (state.chosen.length >= MAX_STOPS) { toast(`코스에는 ${MAX_STOPS}곳까지 넣을 수 있어요. 하나를 먼저 빼 주세요.`); return; }
    state.chosen = [...state.chosen, c];
    render();
    toast(`${c.name}을(를) 넣었어요. 가까운 순으로 순서를 다시 정했어요.`);
    $('candidates').querySelector('button:not([disabled])')?.focus();
  });
  return el('li', { class: 'cand' },
    el('div', {}, el('strong', { text: c.name }), el('span', { class: 'cand-meta', text: ` ${c.sub} · 출발점에서 ${formatDistance(c.distanceM)}${c.onnuri ? ' · 온누리' : ''}` })),
    add);
}

function renderCandidates() {
  const list = state.tab === 'store' || state.tab === 'creator' ? state.stores.filter((s) => s.kind === state.tab) : state.spots.filter((s) => s.kind === state.tab);
  if (!list.length) {
    const msg = state.tab === 'store' || state.tab === 'creator' ? `출발점 ${formatDistance(state.storeRadiusM)} 안에 ${KIND_LABEL[state.tab]}가 없어요.`
      : state.tourAvailable ? `출발점 ${formatDistance(state.spotRadiusM)} 안에 ${KIND_LABEL[state.tab]} 정보가 없어요.` : '관광지 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
    $('candidates').replaceChildren(el('li', { class: 'empty', text: msg }));
    return;
  }
  $('candidates').replaceChildren(...list.slice(0, 12).map(candidateItem));
}

function render() {
  const stops = plan();
  const legs = currentLegs(stops);
  let prev = state.start;
  $('stops').replaceChildren(...stops.map((s, i) => { const item = stopItem(s, i, prev, legs?.[i]); prev = s; return item; }));
  if (!stops.length) $('stops').replaceChildren(el('li', { class: 'empty', text: '들르는 곳이 없어요. 아래에서 넣어 주세요.' }));
  $('summary').textContent = summaryText(stops, legs);
  renderCandidates();
  drawMap(stops, legs);
  requestWalk(stops);
}

function selectTab(kind, focus) {
  state.tab = kind;
  document.querySelectorAll('.add-box [role="tab"]').forEach((t) => {
    const on = t.dataset.kind === kind;
    t.setAttribute('aria-selected', String(on));
    t.tabIndex = on ? 0 : -1;
    if (on && focus) t.focus();
  });
  $('candPanel').setAttribute('aria-labelledby', `tab-${kind}`);
  renderCandidates();
}

// ---------------------------------------------------------------- 시작

async function init() {
  mountAccount($('account'));
  auth.init();
  const q = new URLSearchParams(location.search);
  const market = q.get('market');
  let url;
  if (market && /^\d+$/.test(market)) url = `/api/tour/course?marketId=${market}`;
  else if (q.get('lat') && q.get('lng')) url = `/api/tour/course?${new URLSearchParams({ lat: q.get('lat'), lng: q.get('lng'), label: q.get('label') ?? '' })}`;
  else {
    $('courseTitle').textContent = '출발점을 알 수 없어요';
    $('summary').replaceChildren(el('a', { href: '/trip.html', text: '출발점 고르기' }), '에서 장소를 찾아 시작해 주세요.');
    return;
  }
  let data;
  try {
    const res = await fetch(url);
    if (!res.ok) throw new Error(String(res.status));
    data = await res.json();
  } catch (e) {
    $('courseTitle').textContent = '코스를 만들지 못했어요';
    $('summary').textContent = e.message === '404' ? '시장을 찾을 수 없어요.' : '잠시 후 다시 시도해 주세요.';
    return;
  }
  state.start = data.start;
  state.stores = data.stores.map(fromStore);
  state.spots = data.spots.map(fromSpot);
  state.tourAvailable = data.tourAvailable;
  state.storeRadiusM = data.storeRadiusM;
  state.spotRadiusM = data.spotRadiusM;
  state.chosen = defaultPicks(state.stores, state.spots);
  document.title = `${data.start.name}에서 걷기 - 이음`;
  $('courseTitle').textContent = `${data.start.name}에서 걷기`;
  if (data.start.kind === 'market') {
    $('backLink').href = `/market.html?id=${data.start.id}`;
    $('backText').textContent = '시장';
  } else if (q.get('from') === 'trip') {
    $('backLink').href = '/trip.html';
    $('backText').textContent = '출발점 다시 고르기';
  } else if (q.get('from') === 'home') {
    $('backLink').href = '/';
    $('backText').textContent = '홈';
  }
  state.tourSource = data.source;
  $('note').textContent = noteText();
  document.querySelectorAll('.add-box [role="tab"]').forEach((t) => {
    t.addEventListener('click', () => selectTab(t.dataset.kind));
    t.addEventListener('keydown', (e) => {
      const tabs = [...document.querySelectorAll('.add-box [role="tab"]')];
      const i = tabs.indexOf(t);
      const n = { ArrowRight: (i + 1) % tabs.length, ArrowLeft: (i - 1 + tabs.length) % tabs.length, Home: 0, End: tabs.length - 1 }[e.key];
      if (n === undefined) return;
      e.preventDefault();
      selectTab(tabs[n].dataset.kind, true);
    });
  });
  render();
  try {
    kakao = await loadKakaoMapSdk();
    map = new kakao.maps.Map($('courseMap'), { center: new kakao.maps.LatLng(state.start.latitude, state.start.longitude), level: 5 });
    drawMap(plan());
  } catch (_) {
    $('courseMap').replaceChildren(el('p', { class: 'map-error', text: '지도를 불러오지 못했어요. 아래 목록으로 코스를 확인해 주세요.' }));
  }
}

init();
