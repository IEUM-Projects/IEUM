// 지도 화면 (MAP-01): 현재 위치/지역 기준 반경 검색, 핀 표시, 카테고리 필터, 목록 보기, 가게 요약 카드
import { api, loadKakaoMapSdk, formatDistance, directionsUrl } from './api.js';
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { marketDaySummary } from './market-days.js';

const CATEGORIES = ['음식점', '카페·간식', '도소매', '생활서비스', '공방·공예', '기타'];
const DEFAULT_CENTER = { lat: 37.5665, lng: 126.9780, label: '서울 중구' }; // 위치를 모를 때 시작점 (서울시청)
const LEVEL_BY_RADIUS = { 500: 4, 1000: 5, 3000: 7 };                         // 반경에 맞는 지도 확대 수준

const state = {
  center: { lat: DEFAULT_CENTER.lat, lng: DEFAULT_CENTER.lng },
  radius: 1000,
  categories: new Set(),   // 비어 있으면 전체
  stores: [],
  markets: [],
  showMarkets: true,   // 전통시장 핀 표시 (칩으로 켜고 끔)
  showStores: true,    // 가게 핀 표시. 홈의 '전통시장' 바로가기(/map.html?show=market)로 오면 꺼진 채 시작
                       // 홈의 백년가게 '지도에서 보기'(/map.html?show=store)로 오면 전통시장이 꺼진 채 시작
  view: 'map',
};

let kakao = null;
let map = null;
let markers = [];
let marketMarkers = [];
let radiusCircle = null;
let regionsCache = null;

const $ = (id) => document.getElementById(id);

// DOM 요소를 만드는 작은 도우미. 가게 이름 같은 데이터는 textContent로 넣어서 안전하게 표시
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([key, value]) => {
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value);
  });
  children.flat().filter(Boolean).forEach((child) => node.append(child));
  return node;
}

function toast(message, ms = 3000) {
  const box = $('toast');
  box.textContent = message;
  box.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { box.hidden = true; }, ms);
}

// ---------------------------------------------------------------- 카테고리

// 주소의 ?show=market|store 를 지금 상태에 맞춘다 (새로고침·공유해도 같은 화면)
function syncAddress() {
  const params = new URLSearchParams(location.search);
  if (!state.showStores) params.set('show', 'market');
  else if (!state.showMarkets) params.set('show', 'store');
  else params.delete('show');
  const query = params.toString();
  history.replaceState(null, '', location.pathname + (query ? `?${query}` : ''));
}

function renderChips() {
  const nav = $('categoryChips');
  nav.replaceChildren();
  // 전통시장 켜고 끄기 (가게 분류와 따로 동작)
  const market = el('button', { type: 'button', class: 'chip chip-market', 'aria-pressed': String(state.showMarkets) },
    marketIcon(), '전통시장');
  market.addEventListener('click', () => {
    state.showMarkets = !state.showMarkets;
    if (!state.showMarkets) state.showStores = true;   // 둘 다 꺼지면 빈 지도가 되므로 가게를 다시 켠다
    renderChips(); syncAddress(); search();
  });
  nav.append(market, el('span', { class: 'chip-divider', 'aria-hidden': 'true' }));
  // 가게 분류: 시장만 보는 중이면 아무것도 눌려 있지 않고, 하나를 누르면 가게가 다시 보인다
  const all = el('button', { type: 'button', class: 'chip', 'aria-pressed': String(state.showStores && state.categories.size === 0), text: '전체' });
  all.addEventListener('click', () => { state.showStores = true; state.categories.clear(); renderChips(); syncAddress(); search(); });
  nav.append(all);
  CATEGORIES.forEach((category) => {
    const chip = el('button', { type: 'button', class: 'chip', 'aria-pressed': String(state.showStores && state.categories.has(category)), text: category });
    chip.addEventListener('click', () => {
      if (!state.showStores) {
        state.showStores = true;
        state.categories = new Set([category]);
      } else if (state.categories.has(category)) state.categories.delete(category);
      else state.categories.add(category);
      renderChips();
      syncAddress();
      search();
    });
    nav.append(chip);
  });
}

// ---------------------------------------------------------------- 검색

async function search() {
  hideCard();
  $('researchButton').hidden = true;
  const where = { lat: state.center.lat.toFixed(6), lng: state.center.lng.toFixed(6), radius: state.radius };
  // 가게와 전통시장을 함께 불러온다 (한쪽이 실패해도 다른 쪽은 보여 줌)
  const [stores, markets] = await Promise.allSettled([
    state.showStores ? api.nearbyStores({ ...where, categories: [...state.categories] }) : Promise.resolve([]),
    state.showMarkets ? api.nearbyMarkets(where) : Promise.resolve([]),
  ]);
  state.stores = stores.status === 'fulfilled' ? stores.value : [];
  state.markets = markets.status === 'fulfilled' ? markets.value : [];
  const failed = [stores, markets].find((r) => r.status === 'rejected');
  if (failed) toast(failed.reason.message);
  drawMarkers();
  renderList();
  if (!failed && state.stores.length === 0 && state.markets.length === 0) {
    const what = state.showStores ? '가게' : '전통시장';
    toast(`반경 ${formatDistance(state.radius)} 안에 ${what}${state.showStores ? '가' : '이'} 없어요. 반경을 넓히거나 다른 지역을 골라 보세요.`);
  }
}

// ---------------------------------------------------------------- 지도

// 색은 tokens.css의 값을 읽어서 쓴다 (색을 바꿀 때 tokens.css만 고치면 됨)
const token = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

function pinImage(store) {
  // 백년가게 청록, 로컬크리에이터 주황, 영업 정보 미확인은 회색 (모두 지도 바탕과 3:1 이상)
  const color = store.infoStatus === 'UNVERIFIED' ? token('--pin-unverified')
    : (store.type === 'CREATOR' ? token('--pin-creator') : token('--pin-store'));
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="28" height="36" viewBox="0 0 28 36">`
    + `<path d="M14 0C6.3 0 0 6.1 0 13.7 0 24 14 36 14 36s14-12 14-22.3C28 6.1 21.7 0 14 0z" fill="${color}"/>`
    + `<circle cx="14" cy="13.5" r="5.5" fill="#fff"/></svg>`;
  return new kakao.maps.MarkerImage(
    `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`,
    new kakao.maps.Size(28, 36),
    { offset: new kakao.maps.Point(14, 36) },
  );
}

// 전통시장 핀: 가게 핀(물방울)과 모양이 다른 네모 + 차양 그림 (색을 몰라도 모양으로 구분)
function marketPinImage() {
  const color = token('--pin-market');
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="32" height="38" viewBox="0 0 32 38">`
    + `<path d="M5 0h22a5 5 0 015 5v21a5 5 0 01-5 5h-6l-5 7-5-7H5a5 5 0 01-5-5V5a5 5 0 015-5z" fill="${color}"/>`
    + `<path d="M7 10l2-4h14l2 4M7 10c0 1.5 1 2.5 2.25 2.5S11.5 11.5 11.5 10c0 1.5 1 2.5 2.25 2.5S16 11.5 16 10c0 1.5 1 2.5 2.25 2.5S20.5 11.5 20.5 10c0 1.5 1 2.5 2.25 2.5S25 11.5 25 10M9 13v10h14V13" fill="none" stroke="#fff" stroke-width="1.8" stroke-linejoin="round"/>`
    + `</svg>`;
  return new kakao.maps.MarkerImage(
    `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`,
    new kakao.maps.Size(32, 38),
    { offset: new kakao.maps.Point(16, 38) },
  );
}

function drawMarkers() {
  marketMarkers.forEach((marker) => marker.setMap(null));
  marketMarkers = state.markets.map((market) => {
    const marker = new kakao.maps.Marker({
      map,
      position: new kakao.maps.LatLng(market.latitude, market.longitude),
      image: marketPinImage(),
      title: `${market.name} (전통시장)`,
      zIndex: 1,
    });
    kakao.maps.event.addListener(marker, 'click', () => showMarketCard(market));
    return marker;
  });
  markers.forEach((marker) => marker.setMap(null));
  markers = state.stores.map((store) => {
    const marker = new kakao.maps.Marker({
      map,
      position: new kakao.maps.LatLng(store.latitude, store.longitude),
      image: pinImage(store),
      title: store.name,
    });
    kakao.maps.event.addListener(marker, 'click', () => showCard(store));
    return marker;
  });

  const center = new kakao.maps.LatLng(state.center.lat, state.center.lng);
  if (radiusCircle) radiusCircle.setMap(null);
  radiusCircle = new kakao.maps.Circle({
    map,
    center,
    radius: state.radius,
    strokeWeight: 2,
    strokeColor: token('--color-primary-50'),
    strokeOpacity: 0.9,
    fillColor: token('--color-primary-50'),
    fillOpacity: 0.08,
  });
}

function moveTo(lat, lng) {
  state.center = { lat, lng };
  map.setLevel(LEVEL_BY_RADIUS[state.radius] ?? 5);
  map.setCenter(new kakao.maps.LatLng(lat, lng));
}

// 지도를 반경의 30% 이상 옮기면 "이 지역에서 다시 검색" 버튼을 보여 준다
function onMapMoved() {
  const c = map.getCenter();
  const dLat = (c.getLat() - state.center.lat) * 111320;
  const dLng = (c.getLng() - state.center.lng) * 111320 * Math.cos((state.center.lat * Math.PI) / 180);
  $('researchButton').hidden = Math.hypot(dLat, dLng) < state.radius * 0.3;
}

// ---------------------------------------------------------------- 목록

function renderList() {
  const parts = [
    state.showStores ? `가게 ${state.stores.length}곳` : null,
    state.showMarkets ? `전통시장 ${state.markets.length}곳` : null,
  ].filter(Boolean).join(' · ');
  $('listSummary').textContent = `반경 ${formatDistance(state.radius)} 안 ${parts} · 가까운 순`;
  $('marketListTitle').hidden = state.markets.length === 0;
  $('marketList').replaceChildren(...state.markets.map((market) => el('li', {},
    el('a', { href: `/market.html?id=${market.id}` },
      el('span', { class: 'name', text: market.name }),
      el('span', { class: 'badge-market', text: '전통시장' }),
      el('div', { class: 'meta', text: `${formatDistance(market.distanceM)} · ${marketDaySummary(market)}` }),
    ),
  )));
  $('storeListTitle').hidden = state.markets.length === 0 || state.stores.length === 0;
  const list = $('storeList');
  list.replaceChildren(...state.stores.map((store) => el('li', {},
    el('a', { href: `/store.html?id=${store.id}` },
      el('span', { class: 'name', text: store.name }),
      store.infoStatus === 'UNVERIFIED' ? el('span', { class: 'badge-unverified', text: '영업 정보 미확인' }) : null,
      el('div', { class: 'meta', text: `${formatDistance(store.distanceM)} · ${store.category ?? '분류 없음'} · ${store.roadAddress ?? ''}` }),
    ),
  )));
}

function setView(view) {
  state.view = view;
  $('listView').hidden = view !== 'list';
  $('viewToggle').textContent = view === 'list' ? '지도 보기' : '목록 보기';
  $('researchButton').hidden = true;
  hideCard();
}

// ---------------------------------------------------------------- 가게 카드

function showCard(store) {
  const card = $('storeCard');
  const close = el('button', { type: 'button', class: 'card-close', 'aria-label': '가게 카드 닫기' });
  close.innerHTML = '<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>';
  close.addEventListener('click', hideCard);
  // 조건에 따라 null이 되는 항목은 빼고 넣는다 (그대로 넣으면 화면에 "null" 글자가 찍힘)
  card.replaceChildren(...[
    close,
    el('div', { class: 'badges' },
      el('span', { class: store.type === 'CREATOR' ? 'badge-type creator' : 'badge-type', text: store.type === 'CREATOR' ? '로컬크리에이터' : '백년가게' }),
      el('span', { class: 'badge-category', text: store.category ?? '분류 없음' }),
    ),
    el('div', { class: 'card-title' },
      el('h2', { text: store.name }),
      el('span', { class: 'distance', text: formatDistance(store.distanceM) }),
    ),
    store.roadAddress ? el('p', { class: 'meta', text: store.roadAddress }) : null,
    store.phone ? el('p', { class: 'meta', text: store.phone }) : null,
    store.infoStatus === 'UNVERIFIED'
      ? el('p', { class: 'notice' }, el('span', { class: 'badge-unverified', text: '영업 정보 미확인' }), ' 방문 전 영업 여부를 확인하세요.')
      : null,
    el('div', { class: 'card-actions' },
      el('a', { class: 'button-primary', href: directionsUrl(store), target: '_blank', rel: 'noopener', text: '길찾기' }),
      el('a', { class: 'button-secondary', href: `/store.html?id=${store.id}`, text: '상세 보기' }),
    ),
  ].filter(Boolean));
  card.hidden = false;
}

// ---------------------------------------------------------------- 전통시장 카드

function marketIcon(size = 18) {
  const span = document.createElement('span');
  span.innerHTML = `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true"><path d="M3 10l2-6h14l2 6"/><path d="M3 10c0 1.7 1.3 3 3 3s3-1.3 3-3c0 1.7 1.3 3 3 3s3-1.3 3-3c0 1.7 1.3 3 3 3s3-1.3 3-3"/><path d="M5 13v7h14v-7"/></svg>`;
  return span.firstChild;
}

function showMarketCard(market) {
  const card = $('storeCard');
  const close = el('button', { type: 'button', class: 'card-close', 'aria-label': '시장 카드 닫기' });
  close.innerHTML = '<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>';
  close.addEventListener('click', hideCard);
  // 같은 건물(같은 좌표)에 있는 다른 시장: 핀이 겹쳐 보이므로 카드에서 알려 준다
  const same = state.markets.filter((m) => m.id !== market.id
    && Number(m.latitude) === Number(market.latitude) && Number(m.longitude) === Number(market.longitude));
  const facts = [
    market.storeCount ? `점포 ${market.storeCount.toLocaleString()}개` : null,
    `주차장 ${market.hasParking ? '있음' : '없음'}`,
    `화장실 ${market.hasToilet ? '있음' : '없음'}`,
  ].filter(Boolean).join(' · ');
  card.replaceChildren(...[
    close,
    el('div', { class: 'badges' },
      el('span', { class: 'badge-market' }, marketIcon(14), '전통시장'),
      el('span', { class: 'badge-category', text: market.marketType }),
    ),
    el('div', { class: 'card-title' },
      el('h2', { text: market.name }),
      el('span', { class: 'distance', text: formatDistance(market.distanceM) }),
    ),
    market.address ? el('p', { class: 'meta', text: market.address }) : null,
    el('p', { class: 'market-days', text: `장날: ${marketDaySummary(market)}` }),
    el('p', { class: 'meta', text: facts }),
    same.length ? el('p', { class: 'meta' }, '같은 건물: ',
      ...same.flatMap((m, i) => [i ? ', ' : null, el('a', { href: `/market.html?id=${m.id}`, text: m.name })]).filter(Boolean)) : null,
    el('div', { class: 'card-actions' },
      el('a', { class: 'button-primary', href: directionsUrl({ name: market.name, latitude: market.latitude, longitude: market.longitude }), target: '_blank', rel: 'noopener', text: '길찾기' }),
      el('a', { class: 'button-secondary', href: `/market.html?id=${market.id}`, text: '상세 보기' }),
    ),
  ].filter(Boolean));
  card.hidden = false;
}

function hideCard() {
  $('storeCard').hidden = true;
}

// ---------------------------------------------------------------- 위치·지역

function getCurrentPosition() {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error('이 브라우저는 위치 기능을 지원하지 않아요.'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => resolve({ lat: pos.coords.latitude, lng: pos.coords.longitude }),
      () => reject(new Error('위치 권한이 없어서 지역을 선택해 주세요.')),
      { enableHighAccuracy: false, timeout: 8000, maximumAge: 60000 },
    );
  });
}

async function useMyLocation({ openRegionOnFail }) {
  try {
    const { lat, lng } = await getCurrentPosition();
    $('regionButton').textContent = '내 주변';
    moveTo(lat, lng);
    await search();
  } catch (e) {
    // 지역 선택 창에 같은 설명이 있으므로, 창을 열 때는 안내를 띄우지 않는다 (다른 안내를 덮어쓰지 않게)
    if (openRegionOnFail) openRegionDialog();
    else toast(e.message);
  }
}

async function openRegionDialog() {
  const dialog = $('regionDialog');
  if (!regionsCache) {
    try {
      regionsCache = await api.regions();
    } catch (e) {
      toast(e.message);
      return;
    }
    const sido = $('sidoSelect');
    sido.replaceChildren(...regionsCache.map((s, i) => el('option', { value: String(i), text: s.sidoName })));
    sido.addEventListener('change', fillSigungu);
    fillSigungu();
  }
  if (!dialog.open) dialog.showModal();
}

function fillSigungu() {
  const sido = regionsCache[Number($('sidoSelect').value)];
  $('sigunguSelect').replaceChildren(...sido.sigungus.map((g, i) =>
    el('option', { value: String(i), text: `${g.name} (${g.storeCount}곳)` })));
}

function onRegionChosen() {
  if ($('regionDialog').returnValue !== 'ok' || !regionsCache) return;
  const sido = regionsCache[Number($('sidoSelect').value)];
  const sigungu = sido.sigungus[Number($('sigunguSelect').value)];
  $('regionButton').textContent = `${sido.sidoName} ${sigungu.name}`;
  moveTo(sigungu.centerLat, sigungu.centerLng);
  search();
}

// ---------------------------------------------------------------- 시작

// 홈에서 넘겨준 시작 위치 (?lat=..&lng=..&label=..). 대한민국 범위 밖이거나 숫자가 아니면 무시
function placeFromAddress(params) {
  const lat = Number(params.get('lat'));
  const lng = Number(params.get('lng'));
  if (!params.has('lat') || !params.has('lng') || !(lat >= 33 && lat <= 39 && lng >= 124 && lng <= 132)) return null;
  return { lat, lng, label: (params.get('label') || '지도 중심').slice(0, 30) };
}

// 카카오 로그인에서 돌아오는 처리는 홈(login-return.js)이 맡는다. 지도에서 로그인하면 next로 이 화면에 돌아온다.
async function init() {
  const params = new URLSearchParams(location.search);
  if (params.get('show') === 'market') {
    // 홈의 '전통시장' 바로가기: 시장만, 시장은 띄엄띄엄 있으므로 반경 3km로 시작
    state.showStores = false;
    state.radius = 3000;
  } else if (params.get('show') === 'store') {
    // 홈의 '내 주변 백년가게 → 지도에서 보기': 가게만 (전통시장 칩을 누르면 시장도 보임)
    state.showMarkets = false;
  }
  // 홈에서 1km 안에 가게가 없어 3km로 넓혀 보여 줬다면 같은 반경으로 (정해진 값만 받음)
  if (LEVEL_BY_RADIUS[params.get('radius')]) state.radius = Number(params.get('radius'));
  $('radiusSelect').value = String(state.radius);
  const start = placeFromAddress(params);
  renderChips();
  mountAccount($('account'), { onLogout: () => toast('로그아웃했어요.') });
  const ready = auth.init();
  try {
    kakao = await loadKakaoMapSdk();
  } catch (e) {
    $('map').textContent = e.message;
    return;
  }
  map = new kakao.maps.Map($('map'), {
    center: new kakao.maps.LatLng(DEFAULT_CENTER.lat, DEFAULT_CENTER.lng),
    level: LEVEL_BY_RADIUS[state.radius],
  });
  kakao.maps.event.addListener(map, 'dragend', onMapMoved);
  kakao.maps.event.addListener(map, 'zoom_changed', onMapMoved);
  kakao.maps.event.addListener(map, 'click', hideCard);

  $('researchButton').addEventListener('click', () => {
    const c = map.getCenter();
    state.center = { lat: c.getLat(), lng: c.getLng() };
    $('regionButton').textContent = '지도 중심';
    search();
  });
  $('radiusSelect').addEventListener('change', (e) => {
    state.radius = Number(e.target.value);
    moveTo(state.center.lat, state.center.lng);
    search();
  });
  $('locateButton').addEventListener('click', () => useMyLocation({ openRegionOnFail: false }));
  $('regionButton').addEventListener('click', openRegionDialog);
  $('regionDialog').addEventListener('close', onRegionChosen);
  $('viewToggle').addEventListener('click', () => setView(state.view === 'map' ? 'list' : 'map'));

  // 처음에는 기본 위치로 바로 보여 주고, 내 위치를 받으면 그쪽으로 옮긴다
  if (start) {
    // 홈에서 고른 위치로 바로 시작 (내 위치를 다시 묻지 않음)
    $('regionButton').textContent = start.label;
    moveTo(start.lat, start.lng);
    await search();
    await ready;
    return;
  }
  $('regionButton').textContent = DEFAULT_CENTER.label;
  await search();
  await ready;
  await useMyLocation({ openRegionOnFail: true });
}

init();
