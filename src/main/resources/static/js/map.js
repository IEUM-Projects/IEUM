// 지도 화면 (MAP-01): 현재 위치/지역 기준 반경 검색, 핀 표시, 카테고리 필터, 목록 보기, 가게 요약 카드
import { api, loadKakaoMapSdk, formatDistance, directionsUrl, LOGIN_FAILURE_MESSAGES } from './api.js';
import * as auth from './auth.js';
import { mountAccount } from './header.js';

const CATEGORIES = ['음식점', '카페·간식', '도소매', '생활서비스', '공방·공예', '기타'];
const DEFAULT_CENTER = { lat: 37.5665, lng: 126.9780, label: '서울 중구' }; // 위치를 모를 때 시작점 (서울시청)
const LEVEL_BY_RADIUS = { 500: 4, 1000: 5, 3000: 7 };                         // 반경에 맞는 지도 확대 수준

const state = {
  center: { lat: DEFAULT_CENTER.lat, lng: DEFAULT_CENTER.lng },
  radius: 1000,
  categories: new Set(),   // 비어 있으면 전체
  stores: [],
  view: 'map',
};

let kakao = null;
let map = null;
let markers = [];
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

function renderChips() {
  const nav = $('categoryChips');
  nav.replaceChildren();
  const all = el('button', { type: 'button', class: 'chip', 'aria-pressed': String(state.categories.size === 0), text: '전체' });
  all.addEventListener('click', () => { state.categories.clear(); renderChips(); search(); });
  nav.append(all);
  CATEGORIES.forEach((category) => {
    const chip = el('button', { type: 'button', class: 'chip', 'aria-pressed': String(state.categories.has(category)), text: category });
    chip.addEventListener('click', () => {
      if (state.categories.has(category)) state.categories.delete(category);
      else state.categories.add(category);
      renderChips();
      search();
    });
    nav.append(chip);
  });
}

// ---------------------------------------------------------------- 검색

async function search() {
  hideCard();
  $('researchButton').hidden = true;
  try {
    state.stores = await api.nearbyStores({
      lat: state.center.lat.toFixed(6),
      lng: state.center.lng.toFixed(6),
      radius: state.radius,
      categories: [...state.categories],
    });
  } catch (e) {
    toast(e.message);
    state.stores = [];
  }
  drawMarkers();
  renderList();
  if (state.stores.length === 0) {
    toast(`반경 ${formatDistance(state.radius)} 안에 가게가 없어요. 반경을 넓히거나 다른 지역을 골라 보세요.`);
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

function drawMarkers() {
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
  $('listSummary').textContent = `반경 ${formatDistance(state.radius)} 안 ${state.stores.length}곳 · 가까운 순`;
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

// 카카오 로그인에서 돌아왔을 때 (/?login=success 또는 /?login=failed&reason=...)
// 결과를 안내하고 주소창의 ?login=... 은 지운다. 로그인 상태 표시는 3단계(로그인 화면)에서 연결한다.
// 반환값 true: 카카오 로그인 전에 보던 다른 화면으로 이동했으니 지도는 더 그리지 않아도 됨
async function handleLoginRedirect() {
  const params = new URLSearchParams(location.search);
  const result = params.get('login');
  if (!result) {
    await auth.init();
    return false;
  }
  history.replaceState(null, '', location.pathname);
  if (result === 'success') {
    const user = await auth.init({ force: true });
    const next = auth.consumeNext();
    if (user && next !== '/') {
      location.replace(next);
      return true;
    }
    toast(user ? `${user.nickname}님, 카카오로 로그인했어요.` : '다시 로그인해 주세요.', 5000);
  } else {
    auth.consumeNext();
    toast(LOGIN_FAILURE_MESSAGES[params.get('reason')] ?? LOGIN_FAILURE_MESSAGES.ERROR, 5000);
  }
  return false;
}

async function init() {
  renderChips();
  mountAccount($('account'), { next: '/', onLogout: () => toast('로그아웃했어요.') });
  const loginNotice = handleLoginRedirect();
  if (await loginNotice) return;
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
  $('regionButton').textContent = DEFAULT_CENTER.label;
  await search();
  await loginNotice; // 로그인 결과 안내가 먼저 뜬 뒤에 위치를 확인한다
  await useMyLocation({ openRegionOnFail: true });
}

init();
