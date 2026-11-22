// 지도 화면 (MAP-01): 현재 위치/지역 기준 반경 검색, 핀 표시, 카테고리 필터, 목록 보기, 가게 요약 카드
import { api, loadKakaoMapSdk, formatDistance, routeAttrs } from './api.js';
import { nearestRegion, queryAround, nearHere } from './near-region.js';
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { marketDaySummary } from './market-days.js';
import { MARKET_NEEDS } from './market-facilities.js';
import { categoriesIn, inCategories, isStoreCategory } from './store-categories.js';

const CREATOR = '로컬크리에이터';   // 분류 칩처럼 보이지만 종류(type=CREATOR)로 거른다 (LOC-01). 주소에는 category=로컬크리에이터
const DEFAULT_CENTER = { lat: 37.5665, lng: 126.9780, label: '서울 중구' }; // 위치를 모를 때 시작점 (서울시청)
const LEVEL_BY_RADIUS = { 500: 4, 1000: 5, 3000: 7 };                         // 반경에 맞는 지도 확대 수준

const state = {
  center: { lat: DEFAULT_CENTER.lat, lng: DEFAULT_CENTER.lng },
  radius: 1000,
  categories: new Set(),   // 비어 있으면 전체
  marketNeeds: new Set(),
  onnuri: false,
  creators: false,         // 로컬크리에이터만 (LOC-01)           // 가게: 온누리상품권 가맹으로 확인된 곳만 (DATA-04)  // 시장만 볼 때 거르는 조건 (주차 가능, 비 와도 OK, 아이와 함께). DATA-01
  stores: [],
  markets: [],
  showMarkets: true,   // 전통시장 핀 표시 (칩으로 켜고 끔)
  showStores: true,    // 가게 핀 표시. 홈의 '전통시장' 바로가기(/map.html?show=market)로 오면 꺼진 채 시작
                       // 홈의 백년가게 '지도에서 보기'(/map.html?show=store)로 오면 전통시장이 꺼진 채 시작
  view: 'map',
  // 내 위치로 볼 때 (LOC-02): here 는 실제 위치(기기 안에서만), regionCenter 는 서버에 보내는 구 중심
  here: null,
  regionCenter: null,
};

let kakao = null;
let map = null;
let markers = [];
let marketMarkers = [];
let radiusCircle = null;
let hereDot = null;   // 내 위치 점 (LOC-02)
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

// 주소의 ?show=market|store, ?category= 를 지금 상태에 맞춘다 (새로고침·공유해도 같은 화면)
function syncAddress() {
  const params = new URLSearchParams(location.search);
  if (!state.showStores) params.set('show', 'market');
  else if (!state.showMarkets) params.set('show', 'store');
  else params.delete('show');
  params.delete('category');
  params.delete('need');
  if (state.showStores && state.creators) params.append('category', CREATOR);
  else if (state.showStores) state.categories.forEach((c) => params.append('category', c));
  else state.marketNeeds.forEach((n) => params.append('need', n));
  if (state.showStores && state.onnuri) params.set('onnuri', '1');
  else params.delete('onnuri');
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
  nav.append(market);
  // 시장만 볼 때: 시설 조건 (지도를 다시 부르지 않고 받아 둔 시장에서 거른다)
  if (!state.showStores) {
    Object.entries(MARKET_NEEDS).forEach(([key, need]) => {
      const chip = el('button', { type: 'button', class: 'chip chip-need', 'aria-pressed': String(state.marketNeeds.has(key)), text: need.label });
      chip.addEventListener('click', () => {
        if (state.marketNeeds.has(key)) state.marketNeeds.delete(key);
        else state.marketNeeds.add(key);
        renderChips();
        syncAddress();
        hideCard();
        drawMarkers();
        renderList();
        if (state.markets.length && !shownMarkets().length) toast('조건에 맞는 시장이 없어요. 조건을 줄이거나 반경을 넓혀 보세요.');
      });
      nav.append(chip);
    });
  }
  nav.append(el('span', { class: 'chip-divider', 'aria-hidden': 'true' }));
  // 가게 분류: 시장만 보는 중이면 아무것도 눌려 있지 않고, 하나를 누르면 가게가 다시 보인다
  // CAT-01: '백년가게' = 백년가게 전체 (로컬크리에이터는 오른쪽 칩으로 따로). 분류 칩은 백년가게를 볼 때만 나온다
  const all = el('button', { type: 'button', class: 'chip chip-century', 'aria-pressed': String(state.showStores && !state.creators && state.categories.size === 0), text: '백년가게' });
  all.addEventListener('click', () => {
    const refetch = !state.showStores || state.creators;
    state.showStores = true; state.creators = false; state.categories.clear(); state.marketNeeds.clear();
    renderChips(); syncAddress();
    if (refetch) search();
    else { hideCard(); drawMarkers(); renderList(); }
  });
  nav.append(all);
  // 분류 칩 (CAT-01): 받아 온 백년가게에 실제로 있는 분류만 (+ 지금 골라 둔 분류). 홈 탭과 같은 목록·순서
  const present = state.showStores && !state.creators ? categoriesIn(state.stores) : [];
  const chips = [...new Set([...present, ...state.categories])];
  chips.forEach((category) => {
    const chip = el('button', { type: 'button', class: 'chip', 'aria-pressed': String(state.showStores && !state.creators && state.categories.has(category)), text: category });
    chip.addEventListener('click', () => {
      if (!state.showStores || state.creators) {
        state.creators = false;
        state.showStores = true;
        state.marketNeeds.clear();
        state.categories = new Set([category]);
        renderChips();
        syncAddress();
        search();
        return;
      } else if (state.categories.has(category)) state.categories.delete(category);
      else state.categories.add(category);
      // 다시 묻지 않고 받아 둔 백년가게에서 거른다
      renderChips();
      syncAddress();
      hideCard();
      drawMarkers();
      renderList();
      if (state.stores.length && !shownStores().length) toast('조건에 맞는 가게가 없어요. 분류를 바꾸거나 반경을 넓혀 보세요.');
    });
    nav.append(chip);
  });
  // 로컬크리에이터만 보기 (LOC-01): 한 번 더 누르면 전체로
  const creator = el('button', { type: 'button', class: 'chip chip-creator', 'aria-pressed': String(state.showStores && state.creators), text: CREATOR });
  creator.addEventListener('click', () => {
    state.creators = !(state.showStores && state.creators);
    state.showStores = true;
    state.categories.clear();
    state.marketNeeds.clear();
    renderChips(); syncAddress(); search();
  });
  nav.append(creator);
  // 로컬크리에이터는 반경 안에 적을 수 있어서, 지역 전체를 보는 화면으로 가는 길도 둔다
  if (state.showStores && state.creators) {
    nav.append(el('a', { class: 'chip chip-link', href: '/creators.html', text: '지역별로 모두 보기' }));
  }
  // 가게를 볼 때: 온누리상품권 (받아 둔 가게에서 거른다)
  if (state.showStores) {
    nav.append(el('span', { class: 'chip-divider', 'aria-hidden': 'true' }));
    const onnuri = el('button', { type: 'button', class: 'chip chip-need chip-onnuri', 'aria-pressed': String(state.onnuri), text: '온누리상품권' });
    onnuri.addEventListener('click', () => {
      state.onnuri = !state.onnuri;
      renderChips();
      syncAddress();
      hideCard();
      drawMarkers();
      renderList();
      if (state.onnuri && state.stores.length && !shownStores().length) toast('반경 안에 온누리상품권 가맹으로 확인된 가게가 없어요. 반경을 넓혀 보세요.');
    });
    nav.append(onnuri);
  }
}

/** 화면에 보일 가게 (분류·온누리상품권 조건). 분류는 백년가게를 볼 때만 */
function shownStores() {
  const byCategory = state.creators ? state.stores : inCategories(state.stores, state.categories);
  return state.onnuri ? byCategory.filter((s) => s.onnuri) : byCategory;
}

/** 화면에 보일 시장 (시장만 볼 때는 시설 조건으로 거름) */
function shownMarkets() {
  if (state.showStores || state.marketNeeds.size === 0) return state.markets;
  return state.markets.filter((m) => [...state.marketNeeds].every((key) => MARKET_NEEDS[key].test(m)));
}

// ---------------------------------------------------------------- 검색

async function search() {
  hideCard();
  $('researchButton').hidden = true;
  // 내 위치로 볼 때는 서버에 구 중심과 넓힌 반경만 보내고, 받은 뒤 기기에서 실제 위치로 다시 고른다 (LOC-02)
  const place = state.here ? { ...state.regionCenter, here: state.here } : state.center;
  const where = state.here ? queryAround(place, state.radius)
    : { lat: state.center.lat.toFixed(6), lng: state.center.lng.toFixed(6), radius: state.radius };
  // 가게와 전통시장을 함께 불러온다 (한쪽이 실패해도 다른 쪽은 보여 줌)
  const [stores, markets] = await Promise.allSettled([
    // CAT-01: '가게'는 백년가게만 (로컬크리에이터는 따로 칩). 분류는 받아 온 뒤 기기에서 거른다
    state.showStores ? api.nearbyStores({ ...where, categories: [], type: state.creators ? 'CREATOR' : 'CENTURY' }) : Promise.resolve([]),
    state.showMarkets ? api.nearbyMarkets(where) : Promise.resolve([]),
  ]);
  state.stores = stores.status === 'fulfilled' ? nearHere(stores.value, place, state.radius) : [];
  state.markets = markets.status === 'fulfilled' ? nearHere(markets.value, place, state.radius) : [];
  const failed = [stores, markets].find((r) => r.status === 'rejected');
  if (failed) toast(failed.reason.message);
  renderChips();   // 받아 온 가게에 있는 분류로 칩을 다시
  drawMarkers();
  renderList();
  if (!failed && state.stores.length > 0 && shownStores().length === 0 && !state.onnuri) {
    toast('반경 안에 고른 분류의 가게가 없어요. 분류를 바꾸거나 반경을 넓혀 보세요.');
  } else if (!failed && state.stores.length === 0 && state.markets.length > 0 && shownMarkets().length === 0) {
    toast('조건에 맞는 시장이 없어요. 조건을 줄이거나 반경을 넓혀 보세요.');
  } else if (!failed && state.stores.length === 0 && state.markets.length === 0) {
    const what = state.showStores ? (state.creators ? '로컬크리에이터' : '백년가게') : '전통시장';
    toast(`반경 ${formatDistance(state.radius)} 안에 ${what}${state.showStores ? '가' : '이'} 없어요. 반경을 넓히거나 다른 지역을 골라 보세요.`);
  }
}

// ---------------------------------------------------------------- 지도

// 색은 tokens.css의 값을 읽어서 쓴다 (색을 바꿀 때 tokens.css만 고치면 됨)
const token = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

function pinImage(store) {
  // 백년가게 청록, 로컬크리에이터 주황, 영업 정보 미확인 백년가게는 회색 (모두 지도 바탕과 3:1 이상)
  // 로컬크리에이터는 선정 목록이라 모두 '운영 여부 미확인'이어서 회색 대신 주황 그대로 둔다 (목록·카드에 미확인 표시)
  const color = store.type === 'CREATOR' ? token('--pin-creator')
    : (store.infoStatus === 'UNVERIFIED' ? token('--pin-unverified') : token('--pin-store'));
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
  marketMarkers = shownMarkets().map((market) => {
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
  markers = shownStores().map((store) => {
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

/** 내 위치 점: 지도 위에만 그린다 (좌표는 서버로 가지 않음) */
function drawHere() {
  if (hereDot) hereDot.setMap(null);
  hereDot = null;
  if (!state.here) return;
  const dot = document.createElement('span');
  dot.className = 'here-dot';
  dot.setAttribute('role', 'img');
  dot.setAttribute('aria-label', '내 위치');
  hereDot = new kakao.maps.CustomOverlay({ position: new kakao.maps.LatLng(state.here.lat, state.here.lng), content: dot, zIndex: 3 });
  hereDot.setMap(map);
}

/** 내 위치 모드를 끝낸다 (지역을 고르거나 지도를 옮겨 다시 찾을 때) */
function leaveHere() {
  state.here = null;
  state.regionCenter = null;
  drawHere();
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
    state.showStores ? `${state.creators ? CREATOR : '백년가게'} ${shownStores().length}곳${state.categories.size && !state.creators ? ` (${[...state.categories].join(', ')})` : ''}${state.onnuri ? ' (온누리상품권)' : ''}` : null,
    state.showMarkets ? `전통시장 ${shownMarkets().length}곳` : null,
  ].filter(Boolean).join(' · ');
  const needs = state.showStores ? '' : [...state.marketNeeds].map((key) => MARKET_NEEDS[key].label).join(', ');
  $('listSummary').textContent = `반경 ${formatDistance(state.radius)} 안 ${parts}${needs ? ` (${needs})` : ''} · 가까운 순`;
  $('marketListTitle').hidden = shownMarkets().length === 0;
  $('marketList').replaceChildren(...shownMarkets().map((market) => el('li', {},
    el('a', { href: `/market.html?id=${market.id}` },
      el('span', { class: 'name', text: market.name }),
      el('span', { class: 'badge-market', text: '전통시장' }),
      el('div', { class: 'meta', text: `${formatDistance(market.distanceM)} · ${marketDaySummary(market)}` }),
    ),
  )));
  $('storeListTitle').hidden = shownMarkets().length === 0 || shownStores().length === 0;
  const list = $('storeList');
  list.replaceChildren(...shownStores().map((store) => el('li', {},
    el('a', { href: `/store.html?id=${store.id}` },
      el('span', { class: 'name', text: store.name }),
      store.type === 'CREATOR' ? el('span', { class: 'badge-type creator', text: CREATOR }) : null,
      store.infoStatus === 'UNVERIFIED' ? el('span', { class: 'badge-unverified', text: store.type === 'CREATOR' ? '운영 여부 미확인' : '영업 정보 미확인' }) : null,
      store.onnuri ? el('span', { class: 'badge-onnuri', text: '온누리' }) : null,
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
    store.onnuri ? el('p', { class: 'meta' }, el('span', { class: 'badge-onnuri', text: '온누리상품권 가맹점' })) : null,
    store.infoStatus === 'UNVERIFIED'
      ? (store.type === 'CREATOR'
        ? el('p', { class: 'notice' }, el('span', { class: 'badge-unverified', text: '운영 여부 미확인' }), ' 지원사업 선정 목록이에요. 방문 전 운영 여부를 확인하세요.')
        : el('p', { class: 'notice' }, el('span', { class: 'badge-unverified', text: '영업 정보 미확인' }), ' 방문 전 영업 여부를 확인하세요.'))
      : null,
    el('div', { class: 'card-actions' },
      el('a', { class: 'button-primary', ...routeAttrs(store), text: '길찾기' }),
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
    market.arcade ? '아케이드' : null,
    market.kidsFriendly ? '수유실·놀이방' : null,
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
      el('a', { class: 'button-primary', ...routeAttrs({ name: market.name, latitude: market.latitude, longitude: market.longitude }), text: '길찾기' }),
      el('a', { class: 'button-secondary', href: `/market.html?id=${market.id}`, text: '상세 보기' }),
    ),
  ].filter(Boolean));
  card.hidden = false;
}

function hideCard() {
  $('storeCard').hidden = true;
}

// ---------------------------------------------------------------- 위치·지역

async function useMyLocation({ openRegionOnFail, quiet = false }) {
  try {
    // 내 위치에서 가장 가까운 시·군·구로 (위치는 기기 안에서만 쓰고 서버로 보내지 않음, POL-01)
    let region;
    try {
      region = await nearestRegion({ maximumAge: 60000 });
    } catch (_) {
      throw new Error('위치를 알 수 없어서 지역을 선택해 주세요.');
    }
    // 지도는 실제 내 위치로 옮기고, 서버에는 구 중심만 보낸다 (LOC-02)
    state.here = region.here;
    state.regionCenter = { lat: region.lat, lng: region.lng };
    $('regionButton').textContent = '내 위치';
    moveTo(region.here.lat, region.here.lng);
    drawHere();
    await search();
  } catch (e) {
    // 지역 선택 창에 같은 설명이 있으므로, 창을 열 때는 안내를 띄우지 않는다 (다른 안내를 덮어쓰지 않게)
    if (openRegionOnFail) openRegionDialog();
    else if (quiet) await search();   // 홈에서 넘어온 구 중심으로 그대로 찾는다
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
  leaveHere();
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
  // 홈 메뉴의 '백년가게 → 음식점' 등: /map.html?show=store&category=음식점 (정해진 분류만 받음)
  params.getAll('category').filter(isStoreCategory).forEach((c) => state.categories.add(c));
  // 메뉴의 '동네 찾기 → 로컬크리에이터': /map.html?show=store&category=로컬크리에이터
  if (state.showStores && params.getAll('category').includes(CREATOR)) { state.creators = true; state.categories.clear(); }
  // 시장만 볼 때의 시설 조건: /map.html?show=market&need=parking (정해진 값만 받음)
  if (!state.showStores) params.getAll('need').filter((n) => MARKET_NEEDS[n]).forEach((n) => state.marketNeeds.add(n));
  state.onnuri = state.showStores && params.get('onnuri') === '1';
  // 홈 메뉴의 '지역별 보기': 내 위치를 묻지 않고 지역 고르기 창부터 연다
  const pickRegion = params.get('pick') === 'region';
  if (pickRegion) {
    params.delete('pick');
    history.replaceState(null, '', location.pathname + (params.toString() ? `?${params}` : ''));
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
    leaveHere();   // 직접 옮긴 지도 중심으로 찾는다 (내가 고른 곳이므로 실제 위치와 상관없음)
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
    // 홈에서 '내 주변'으로 왔으면(near=1) 이 기기에서 내 위치를 다시 받아 그쪽으로 (못 받으면 구 중심 그대로)
    if (params.get('near') === '1') await useMyLocation({ openRegionOnFail: false, quiet: true });
    else await search();
    await ready;
    return;
  }
  $('regionButton').textContent = DEFAULT_CENTER.label;
  await search();
  await ready;
  if (pickRegion) {
    openRegionDialog();
    return;
  }
  await useMyLocation({ openRegionOnFail: true });
}

init();
