// 홈 화면 (UI-02): 내 주변 백년가게, 이번 주 근처 축제, 오늘 장 서는 시장
import { api, formatDistance } from './api.js';
import { mountAccount } from './header.js';
import { handleLoginReturn } from './login-return.js';
import { WEEKDAYS, ymd, weekday, todayString, periodText, status } from './festival-format.js';
import { digitsOf, isMarketDay, marketDaySummary } from './market-days.js';

const DEFAULT_PLACE = { lat: 37.5665, lng: 126.9780, label: '서울 중구 (기본 위치)' };   // 서울시청
const PLACE_KEY = 'ieum_home_place';    // 고른 지역을 이 브라우저에만 기억 (편의 기능)
const FESTIVAL_RADIUS = 30000;
const $ = (id) => document.getElementById(id);

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

function toast(message, ms = 5000) {
  const box = $('toast');
  box.textContent = message;
  box.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { box.hidden = true; }, ms);
}

// ---------------------------------------------------------------- 위치

function savedPlace() {
  try {
    const p = JSON.parse(localStorage.getItem(PLACE_KEY));
    return p && typeof p.lat === 'number' && typeof p.lng === 'number' && p.label ? p : null;
  } catch (_) {
    return null;
  }
}

function savePlace(place) {
  try {
    if (place) localStorage.setItem(PLACE_KEY, JSON.stringify(place));
    else localStorage.removeItem(PLACE_KEY);
  } catch (_) { /* 저장이 안 되는 브라우저(사생활 보호 모드 등)는 이번에만 쓴다 */ }
}

function myLocation() {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error('이 브라우저는 위치 기능을 지원하지 않아요.'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => resolve({ lat: pos.coords.latitude, lng: pos.coords.longitude, label: '내 주변' }),
      () => reject(new Error('위치 권한이 없어서 기본 위치로 보여 드려요.')),
      { timeout: 8000, maximumAge: 300000 },
    );
  });
}

// ---------------------------------------------------------------- 내 주변 백년가게

function storeItem(s) {
  return el('li', {},
    el('a', { href: `/store.html?id=${s.id}`, class: 'item' },
      el('span', { class: 'item-main' },
        el('strong', { class: 'item-name', text: s.name }),
        s.infoStatus === 'UNVERIFIED' ? el('span', { class: 'badge-unverified', text: '영업 정보 미확인' }) : null),
      el('span', { class: 'item-meta', text: [s.category, s.roadAddress].filter(Boolean).join(' · ') }),
      el('span', { class: 'item-side', text: formatDistance(s.distanceM) }),
    ));
}

let storeRadius = 1000;   // 홈 목록에 쓴 반경 (지도 링크에 넘김)

async function loadStores(place) {
  $('storesStatus').textContent = '불러오는 중...';
  $('storeList').replaceChildren();
  try {
    let stores = await api.nearbyStores({ lat: place.lat.toFixed(6), lng: place.lng.toFixed(6), radius: 1000 });
    let radius = 1000;
    if (!stores.length) {
      radius = 3000;
      stores = await api.nearbyStores({ lat: place.lat.toFixed(6), lng: place.lng.toFixed(6), radius });
    }
    storeRadius = radius;
    updateMapLinks(place);
    $('storeList').replaceChildren(...stores.slice(0, 4).map(storeItem));
    $('storesStatus').textContent = stores.length
      ? (radius > 1000 ? '1km 안에는 없어서 3km 안의 가게를 보여 드려요.' : '')
      : '3km 안에 백년가게가 없어요. 위치를 바꿔 보세요.';
  } catch (_) {
    $('storesStatus').textContent = '가게를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
}

// ---------------------------------------------------------------- 이번 주 근처 축제

/** 이번 주 월요일~일요일 'YYYY-MM-DD' 7개 */
function thisWeek(today = todayString()) {
  const offset = (weekday(today) + 6) % 7;   // 월요일 = 0
  const [y, m, d] = today.split('-').map(Number);
  return [...Array(7).keys()].map((i) => {
    const t = new Date(Date.UTC(y, m - 1, d - offset + i));
    return ymd(t.getUTCFullYear(), t.getUTCMonth() + 1, t.getUTCDate());
  });
}

function renderWeek(days, festivals) {
  const today = todayString();
  $('weekStrip').replaceChildren(...days.map((day) => {
    const count = festivals.filter((f) => !f.longTerm && f.startDate <= day && f.endDate >= day).length;
    const label = `${Number(day.slice(5, 7))}월 ${Number(day.slice(8))}일 ${WEEKDAYS[weekday(day)]}요일, 근처 축제 ${count}개${day === today ? ', 오늘' : ''}`;
    const link = el('a', {
      href: `/festivals.html?month=${day.slice(0, 7)}&date=${day}`,
      class: `week-day${day === today ? ' today' : ''}${count ? ' has' : ''}`,
      'aria-label': label,
    },
    el('span', { class: 'wd', text: WEEKDAYS[weekday(day)] }),
    el('span', { class: 'num', text: String(Number(day.slice(8))) }),
    el('span', { class: count ? 'dot' : 'dot none' }));
    if (day === today) link.setAttribute('aria-current', 'date');
    return el('li', {}, link);
  }));
}

function festivalItem(f) {
  const s = status(f);
  return el('li', {},
    el('a', { href: `/festival.html?id=${f.id}`, class: 'item' },
      el('span', { class: 'item-main' },
        el('span', { class: `status ${s.cls}`, text: s.text }),
        el('strong', { class: 'item-name', text: f.name })),
      el('span', { class: 'item-meta', text: [periodText(f), f.regionName].filter(Boolean).join(' · ') }),
      el('span', { class: 'item-side', text: formatDistance(f.distanceM) }),
    ));
}

async function loadFestivals(place) {
  const days = thisWeek();
  $('festivalsStatus').textContent = '불러오는 중...';
  $('festivalList').replaceChildren();
  try {
    const festivals = await api.festivalsNearby({
      lat: place.lat.toFixed(6), lng: place.lng.toFixed(6), from: days[0], to: days[6], radius: FESTIVAL_RADIUS,
    });
    renderWeek(days, festivals);
    // 짧은 축제 먼저, 기간이 긴 행사는 뒤로
    const sorted = [...festivals.filter((f) => !f.longTerm), ...festivals.filter((f) => f.longTerm)];
    $('festivalList').replaceChildren(...sorted.slice(0, 4).map(festivalItem));
    $('festivalsStatus').textContent = festivals.length
      ? `${FESTIVAL_RADIUS / 1000}km 안 ${festivals.length}개`
      : `이번 주 ${FESTIVAL_RADIUS / 1000}km 안에는 등록된 축제가 없어요. 축제 달력에서 다른 지역도 볼 수 있어요.`;
  } catch (_) {
    renderWeek(days, []);
    $('festivalsStatus').textContent = '축제를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
}

// ---------------------------------------------------------------- 오늘 장 서는 시장

function marketItem(m) {
  return el('li', {},
    el('a', { href: `/market.html?id=${m.id}`, class: 'item' },
      el('span', { class: 'item-main' },
        el('span', { class: 'badge-market', text: '전통시장' }),
        el('strong', { class: 'item-name', text: m.name })),
      el('span', { class: 'item-meta', text: marketDaySummary(m) }),
      el('span', { class: 'item-side', text: formatDistance(m.distanceM) }),
    ));
}

async function loadMarkets(place) {
  $('marketsStatus').textContent = '불러오는 중...';
  $('marketList').replaceChildren();
  try {
    const markets = await api.nearbyMarkets({ lat: place.lat.toFixed(6), lng: place.lng.toFixed(6), radius: 5000 });
    const today = todayString();
    // 오늘 정기장이 서는 시장을 먼저, 나머지는 가까운 순
    const fair = markets.filter((m) => isMarketDay(digitsOf(m.marketDays), today));
    const rest = markets.filter((m) => !fair.includes(m));
    $('marketsTitle').textContent = fair.length ? '오늘 장 서는 시장' : '가까운 전통시장';
    $('marketList').replaceChildren(...[...fair, ...rest].slice(0, 4).map(marketItem));
    $('marketsStatus').textContent = markets.length
      ? (fair.length ? `오늘 장이 서는 시장 ${fair.length}곳 · 5km 안` : '5km 안 · 오늘 정기장이 서는 시장은 없어요')
      : '5km 안에 전통시장이 없어요. 위치를 바꿔 보세요.';
  } catch (_) {
    $('marketsStatus').textContent = '시장을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
}

// ---------------------------------------------------------------- 그리기

// 지도로 가는 링크에 지금 보고 있는 위치를 넣는다 (홈에서 고른 지역 그대로 지도가 열리게)
function updateMapLinks(place) {
  const where = new URLSearchParams({ lat: place.lat.toFixed(6), lng: place.lng.toFixed(6), label: place.label });
  document.querySelectorAll('a[data-map]').forEach((a) => {
    const params = new URLSearchParams(where);
    if (a.dataset.map === 'market') params.set('show', 'market');   // 전통시장만 보이는 지도
    if (a.dataset.map === 'store') {
      params.set('show', 'store');                                   // 백년가게만 보이는 지도
      if (storeRadius !== 1000) params.set('radius', String(storeRadius));   // 홈에서 넓혀 보여 준 반경 그대로
    }
    a.href = `/map.html?${params}`;
  });
}

function show(place) {
  $('placeLabel').textContent = place.label;
  updateMapLinks(place);
  return Promise.all([loadStores(place), loadFestivals(place), loadMarkets(place)]);
}

// ---------------------------------------------------------------- 위치 바꾸기 창

let regions = null;

function fillSigungu() {
  const sido = regions.find((r) => r.sidoCode === $('sidoSelect').value);
  $('sigunguSelect').replaceChildren(...(sido?.sigungus ?? []).map((g) => el('option', { value: g.code, text: g.name })));
}

async function openPlaceDialog() {
  $('placeError').textContent = '';
  if (!regions) {
    try {
      regions = await api.regions();
    } catch (_) {
      toast('지역 목록을 불러오지 못했어요.');
      return;
    }
    $('sidoSelect').replaceChildren(...regions.map((r) => el('option', { value: r.sidoCode, text: r.sidoName })));
    fillSigungu();
  }
  $('placeDialog').showModal();
}

function onPlaceChosen() {
  if ($('placeDialog').returnValue !== 'ok') return;
  const sido = regions.find((r) => r.sidoCode === $('sidoSelect').value);
  const sigungu = sido?.sigungus.find((g) => g.code === $('sigunguSelect').value);
  if (!sigungu) return;
  const place = { lat: sigungu.centerLat, lng: sigungu.centerLng, label: `${sido.sidoName} ${sigungu.name}` };
  savePlace(place);
  show(place);
}

async function useMyLocationFromDialog() {
  $('placeError').textContent = '';
  const button = $('useMyLocation');
  button.disabled = true;
  button.setAttribute('aria-busy', 'true');
  try {
    const place = await myLocation();
    savePlace(null);    // 내 위치를 쓰면 저장해 둔 지역은 지운다 (다음에도 내 위치로)
    $('placeDialog').close('mine');
    show(place);
  } catch (e) {
    $('placeError').textContent = e.message.replace('기본 위치로 보여 드려요', '지역을 골라 주세요');
  } finally {
    button.disabled = false;
    button.removeAttribute('aria-busy');
  }
}

// ---------------------------------------------------------------- 시작

async function init() {
  mountAccount($('account'), { next: '/', onLogout: () => toast('로그아웃했어요.') });
  if (await handleLoginReturn((message) => toast(message))) return;   // 카카오 로그인 후 보던 화면으로 이동

  $('placeButton').addEventListener('click', openPlaceDialog);
  $('sidoSelect').addEventListener('change', fillSigungu);
  $('placeDialog').addEventListener('close', onPlaceChosen);
  $('useMyLocation').addEventListener('click', useMyLocationFromDialog);

  const saved = savedPlace();
  if (saved) {
    await show(saved);
    return;
  }
  try {
    await show(await myLocation());
  } catch (e) {
    await show(DEFAULT_PLACE);
  }
}

init();

