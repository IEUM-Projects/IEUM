// 홈 화면 (UI-02 → UI-03 새 디자인)
// 주요 소식(슬라이드), 내 주변 백년가게(분류 탭), 이번 주 근처 축제, 오늘 장 서는 시장 + 5일장 달력, 주변 관광지와 걷기 코스, 제보 안내
// 모든 내용은 서버의 실제 데이터로만 채운다 (지어낸 숫자·문구 없음).
import { api, formatDistance } from './api.js';
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { handleLoginReturn } from './login-return.js';
import { changeTextSize, textSize, TEXT_SIZES } from './text-size.js';
import { WEEKDAYS, ymd, weekday, daysIn, todayString, periodText, status, photoCredit } from './festival-format.js';
import { digitsOf, cycleLabel, isMarketDay, marketDaySummary } from './market-days.js';
import { cleanQuery, clearRecent, recentSearches, removeRecent, SEARCH_EXAMPLES } from './search-common.js';
import { THEME_ICONS, placeLinkQuery } from './theme-common.js';

const DEFAULT_PLACE = { lat: 37.5665, lng: 126.9780, label: '서울 중구', isDefault: true };   // 서울시청
const PLACE_KEY = 'ieum_home_place';    // 고른 지역을 이 브라우저에만 기억 (편의 기능)
const FESTIVAL_RADIUS = 30000;
const MARKET_RADIUS = 5000;
const HERO_INTERVAL = 7000;
const $ = (id) => document.getElementById(id);

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([key, value]) => {
    if (value === null || value === undefined || value === false) return;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value === true ? '' : value);
  });
  children.flat().filter((c) => c !== null && c !== undefined && c !== false).forEach((child) => node.append(child));
  return node;
}

/** 선 아이콘 (aria-hidden). d: path 목록 */
function icon(paths, cls = '') {
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

const ICONS = {
  food: ['M7 3v8M4 3v5a3 3 0 0 0 6 0V3M7 11v10M16 3v18M16 3c4.5 2 4.5 9 0 10'],
  cafe: ['M4 8h13v5a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5Z', 'M17 9h1.5a2.5 2.5 0 0 1 0 5H17M8 2v3M12 2v3'],
  retail: ['M5 8h14l1 13H4Z', 'M9 9V6a3 3 0 0 1 6 0v3'],
  service: ['M14.5 6.5a4 4 0 0 0-5 5L3 18l3 3 6.5-6.5a4 4 0 0 0 5-5l-2.8 2.8-3-3Z'],
  craft: ['M12 3c3 3 5 6 5 9a5 5 0 0 1-10 0c0-3 2-6 5-9Z', 'M12 12v9'],
  store: ['M4 10v10h16V10M3 10l2-6h14l2 6', 'M3 10a3 3 0 0 0 6 0 3 3 0 0 0 6 0 3 3 0 0 0 6 0M9 20v-5h6v5'],
  market: ['M3 10l2-6h14l2 6', 'M3 10a3 3 0 0 0 6 0 3 3 0 0 0 6 0 3 3 0 0 0 6 0', 'M5 13v7h14v-7'],
  festival: ['M4 21V4M4 4l9 3-9 4', 'M10 21h10M15 21V11l-3-3', 'M15 11l3-3'],
  check: ['M5 12.5 10 17l9-10'],
  info: ['M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18Z', 'M12 11v6M12 7.5v.5'],
  clock: ['M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18Z', 'M12 7v5l3 2'],
  pin: ['M20 10c0 5-8 11-8 11S4 15 4 10a8 8 0 1 1 16 0Z', 'M12 7.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5Z'],
  arrow: ['M5 12h14m-5-5 5 5-5 5'],
  chevron: ['m9 6 6 6-6 6'],
  plus: ['M12 5v14M5 12h14'],
  minus: ['M5 12h14'],
};
const CATEGORY_ICON = { '음식점': 'food', '카페·간식': 'cafe', '도소매': 'retail', '생활서비스': 'service', '공방·공예': 'craft' };

function toast(message, ms = 5000) {
  const box = $('toast');
  box.textContent = message;
  box.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { box.hidden = true; }, ms);
}

/** '내 주변' 또는 '서울 중구 기준' */
function placeText(place = latest.place) {
  return place.label === '내 주변' ? '내 주변' : `${place.label} 기준`;
}

// 지금 화면에 그린 데이터 (주요 소식 슬라이드를 만들 때 씀)
const latest = { place: null, stores: [], storeRadius: 1000, festivals: [], markets: [], fair: [] };

// ================================================================ 위치

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

/** 지도로 가는 링크에 지금 보고 있는 위치를 넣는다 (홈에서 고른 지역 그대로 지도가 열리게) */
function updateMapLinks() {
  const place = latest.place;
  if (!place) return;
  document.querySelectorAll('a[data-map]').forEach((a) => {
    const params = new URLSearchParams({ lat: place.lat.toFixed(6), lng: place.lng.toFixed(6), label: place.label });
    if (a.dataset.map === 'market') params.set('show', 'market');             // 전통시장만
    if (a.dataset.map === 'store') {
      params.set('show', 'store');                                             // 백년가게만
      if (a.dataset.category) params.set('category', a.dataset.category);
      else if (latest.storeRadius !== 1000) params.set('radius', String(latest.storeRadius));   // 홈에서 넓혀 보여 준 반경 그대로
    }
    a.href = `/map.html?${params}`;
  });
  // 테마로 걷기: 홈의 위치를 넘겨 가까운 순으로 (data-theme="" 은 목록, 값이 있으면 그 테마)
  document.querySelectorAll('a[data-theme]').forEach((a) => {
    const id = a.dataset.theme;
    a.href = id ? `/theme.html?id=${id}&${placeLinkQuery(place)}` : `/themes.html?${placeLinkQuery(place)}`;
  });
}

// ================================================================ 위쪽 메뉴

function setupNav() {
  const items = [...document.querySelectorAll('#mainNav .nav-item')];
  const close = (except) => items.forEach((item) => {
    if (item === except) return;
    item.querySelector('.nav-button').setAttribute('aria-expanded', 'false');
  });
  const wide = matchMedia('(min-width: 1024px) and (hover: hover)');
  items.forEach((item) => {
    const button = item.querySelector('.nav-button');
    button.addEventListener('click', () => {
      const open = button.getAttribute('aria-expanded') !== 'true';
      close(item);
      button.setAttribute('aria-expanded', String(open));
    });
    // 마우스를 올리면 펼치고, 벗어나면 접는다 (넓은 화면에서 마우스를 쓸 때만)
    item.addEventListener('mouseenter', () => { if (wide.matches) { close(item); button.setAttribute('aria-expanded', 'true'); } });
    item.addEventListener('mouseleave', () => { if (wide.matches) button.setAttribute('aria-expanded', 'false'); });
    // 키보드: Esc로 접고 메뉴 버튼으로 초점, 펼친 메뉴 밖으로 Tab 하면 접는다
    item.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && button.getAttribute('aria-expanded') === 'true') {
        button.setAttribute('aria-expanded', 'false');
        button.focus();
      }
    });
    item.addEventListener('focusout', (event) => {
      if (!item.contains(event.relatedTarget)) button.setAttribute('aria-expanded', 'false');
    });
  });
  document.addEventListener('click', (event) => {
    if (!event.target.closest('#mainNav')) close();
  });
  // 같은 화면 안으로 가는 메뉴(#markets 등)를 누르면 메뉴를 접는다
  document.querySelectorAll('#mainNav a[href^="#"]').forEach((a) => a.addEventListener('click', () => close()));
}

/** 전체 메뉴 창: 위쪽 메뉴와 같은 내용을 펼침 목록으로 */
function setupMenuDialog() {
  const dialog = $('menuDialog');
  const nav = $('menuNav');
  nav.replaceChildren(...[...document.querySelectorAll('#mainNav .nav-item')].map((item, i) => {
    const title = item.querySelector('.nav-button').textContent.trim();
    const list = item.querySelector('.sub').cloneNode(true);
    list.id = `menu-sub-${i}`;
    list.className = 'menu-sub';
    list.hidden = true;
    const toggle = el('button', { type: 'button', class: 'menu-toggle', 'aria-expanded': 'false', 'aria-controls': list.id },
      el('span', { text: title }), icon(ICONS.plus, 'icon-plus'), icon(ICONS.minus, 'icon-minus'));
    toggle.addEventListener('click', () => {
      const open = toggle.getAttribute('aria-expanded') !== 'true';
      toggle.setAttribute('aria-expanded', String(open));
      list.hidden = !open;
    });
    list.querySelectorAll('a').forEach((a) => {
      a.append(icon(ICONS.chevron));
      a.addEventListener('click', () => dialog.close());   // 같은 화면 안(#markets)으로 갈 때도 창을 닫는다
    });
    return el('div', { class: 'menu-group' }, toggle, list);
  }));

  function renderUser() {
    const user = auth.getUser();
    if (!user) {
      $('menuUser').replaceChildren(
        el('p', { text: '로그인하면 가게 정보를 제보하고 처리 결과를 볼 수 있어요.' }),
        el('div', { class: 'menu-user-actions' },
          el('a', { class: 'button-primary', href: auth.loginUrl('/'), text: '로그인' }),
          el('a', { class: 'button-secondary', href: '/signup.html', text: '회원가입' })));
      return;
    }
    const logout = el('button', { type: 'button', class: 'button-secondary', text: '로그아웃' });
    logout.addEventListener('click', async () => {
      await auth.logout();
      dialog.close();
      toast('로그아웃했어요.');
    });
    $('menuUser').replaceChildren(
      el('p', {}, el('strong', { text: `${user.nickname}님` }), ', 반가워요.'),
      el('div', { class: 'menu-user-actions' },
        el('a', { class: 'button-secondary', href: '/mypage.html', text: '내 정보' }),
        user.role === 'ADMIN' ? el('a', { class: 'button-secondary', href: '/admin.html', text: '관리자' }) : null,
        logout));
  }

  $('menuButton').addEventListener('click', () => {
    renderUser();
    dialog.showModal();
    $('menuClose').focus();
  });
  $('menuClose').addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', () => $('menuButton').focus());
}

function setupTextSize() {
  const update = () => document.querySelectorAll('.size-button').forEach((b) => {
    const step = Number(b.dataset.step);
    const atEdge = step < 0 ? textSize() === 0 : textSize() === TEXT_SIZES.length - 1;
    b.setAttribute('aria-disabled', String(atEdge));
  });
  document.querySelectorAll('.size-button').forEach((b) => b.addEventListener('click', () => {
    if (b.getAttribute('aria-disabled') === 'true') {
      toast(Number(b.dataset.step) < 0 ? '지금이 가장 작은 글자 크기예요.' : '지금이 가장 큰 글자 크기예요.');
      return;
    }
    toast(`글자 크기: ${changeTextSize(Number(b.dataset.step))}`);
    update();
  }));
  update();
}

// ================================================================ 검색 창

function setupSearch() {
  const dialog = $('searchDialog');
  const chip = (q) => el('a', { class: 'search-chip', href: `/search.html?q=${encodeURIComponent(q)}`, text: q });
  function renderRecent() {
    const recent = recentSearches();
    $('homeRecent').replaceChildren(...recent.map((q) => {
      const remove = el('button', { type: 'button', class: 'chip-remove', 'aria-label': `최근 검색어 ${q} 지우기`, text: '×' });
      remove.addEventListener('click', () => { removeRecent(q); renderRecent(); $('homeQ').focus(); });
      return el('li', { class: 'chip-with-remove' }, chip(q), remove);
    }));
    $('homeRecentEmpty').hidden = recent.length > 0;
    $('homeClearRecent').hidden = recent.length === 0;
  }
  $('homeExamples').replaceChildren(...SEARCH_EXAMPLES.map((q) => el('li', {}, chip(q))));
  $('searchButton').addEventListener('click', () => {
    renderRecent();
    dialog.showModal();
    $('homeQ').focus();
  });
  $('searchClose').addEventListener('click', () => dialog.close());
  $('homeClearRecent').addEventListener('click', () => { clearRecent(); renderRecent(); $('homeQ').focus(); });
  dialog.addEventListener('close', () => $('searchButton').focus());
  // 바깥(어두운 부분)을 누르면 닫기
  dialog.addEventListener('click', (event) => { if (event.target === dialog) dialog.close(); });
  $('homeSearchForm').addEventListener('submit', (event) => {
    const q = cleanQuery($('homeQ').value);
    if (!q) {
      event.preventDefault();
      $('homeQ').focus();
      return;
    }
    $('homeQ').value = q;
  });
}

// ================================================================ ① 주요 소식 (슬라이드)

const hero = { index: 0, timer: null, playing: false, paused: false };

/** 지금 데이터로 슬라이드 내용을 만든다. 데이터가 없는 소식은 빼고, 지어낸 문구는 쓰지 않는다 */
function heroSlides() {
  const slides = [];
  // 고르는 순서: 짧은 축제(사진 있음) → 짧은 축제 → 긴 행사(사진 있음) → 아무거나
  const fs = latest.festivals;
  const fest = fs.find((f) => !f.longTerm && f.imageUrl) ?? fs.find((f) => !f.longTerm) ?? fs.find((f) => f.imageUrl) ?? fs[0];
  if (fest) {
    slides.push({
      kind: 'festival', eyebrow: '이번 주 근처 축제', title: fest.name,
      detail: [periodText(fest), fest.regionName ?? fest.place, formatDistance(fest.distanceM)].filter(Boolean).join(' · '),
      link: `/festival.html?id=${fest.id}`, linkText: '축제 자세히 보기',
      image: fest.imageUrl, credit: fest.imageUrl ? photoCredit(fest.imageLicense) : null, iconName: 'festival',
      // 사진이 없으면 끝나는 날을 크게 (예: 12.31 까지)
      stat: `${Number(fest.endDate.slice(5, 7))}.${Number(fest.endDate.slice(8))}`, statUnit: '까지',
    });
  }
  if (latest.fair.length) {
    slides.push({
      kind: 'market', eyebrow: '오늘, 장날', title: `오늘 장 서는 시장 ${latest.fair.length}곳`,
      detail: `${placeText()} ${MARKET_RADIUS / 1000}km 안 · 가장 가까운 곳: ${latest.fair[0].name} ${formatDistance(latest.fair[0].distanceM)}`,
      link: '#markets', linkText: '오늘 장 서는 시장 보기', iconName: 'market', stat: String(latest.fair.length), statUnit: '곳',
    });
  } else if (latest.markets.length) {
    slides.push({
      kind: 'market', eyebrow: '가까운 전통시장', title: `${MARKET_RADIUS / 1000}km 안 전통시장 ${latest.markets.length}곳`,
      detail: `오늘 정기장이 서는 곳은 없어요 · 가장 가까운 곳: ${latest.markets[0].name} ${formatDistance(latest.markets[0].distanceM)}`,
      link: '#markets', linkText: '시장 목록 보기', iconName: 'market', stat: String(latest.markets.length), statUnit: '곳',
    });
  }
  if (latest.stores.length) {
    const near = latest.stores[0];
    slides.push({
      kind: 'store', eyebrow: '내 주변 백년가게', title: `${formatDistance(latest.storeRadius)} 안 백년가게 ${latest.stores.length}곳`,
      detail: `30년 넘게 이어 온 가게들 · 가장 가까운 곳: ${near.name} ${formatDistance(near.distanceM)}`,
      link: document.querySelector('#stores .more').getAttribute('href'), linkText: '지도에서 가게 보기',
      iconName: 'store', stat: String(latest.stores.length), statUnit: '곳',
    });
  }
  return slides;
}

function slideNode(s, i, total) {
  const visual = s.image
    ? el('figure', { class: 'slide-photo' },
      el('img', { src: s.image, alt: `${s.title} 대표 사진`, referrerpolicy: 'no-referrer', loading: i === 0 ? 'eager' : 'lazy' }),
      el('figcaption', { text: s.credit }))
    : el('div', { class: `slide-art art-${s.kind}`, 'aria-hidden': 'true' },
      icon(ICONS[s.iconName], 'art-icon'),
      s.stat ? el('p', { class: 'art-stat' }, el('b', { text: s.stat }), el('span', { text: s.statUnit })) : null);
  return el('div', { class: `slide slide-${s.kind}`, role: 'group', 'aria-roledescription': '소식', 'aria-label': `${i + 1} / ${total}`, hidden: i !== hero.index },
    el('div', { class: 'inner slide-inner' },
      el('div', { class: 'slide-text' },
        el('p', { class: 'eyebrow', text: s.eyebrow }),
        el('h2', { class: 'slide-title', text: s.title }),
        el('p', { class: 'slide-detail', text: s.detail }),
        el('a', { class: 'slide-link', href: s.link }, s.linkText, icon(ICONS.arrow))),
      visual));
}

function renderHero() {
  const slides = heroSlides();
  if (hero.index >= slides.length) hero.index = 0;
  if (!slides.length) {
    $('heroSlides').replaceChildren(el('div', { class: 'slide slide-empty', role: 'group' },
      el('div', { class: 'inner slide-inner' },
        el('div', { class: 'slide-text' },
          el('p', { class: 'eyebrow', text: '주요 소식' }),
          el('h2', { class: 'slide-title', text: '오늘은 어느 골목을 걸어 볼까요?' }),
          el('p', { class: 'slide-detail', text: '이 근처에는 아직 보여 드릴 소식이 없어요. 위치를 바꿔 보세요.' })))));
    $('heroControls').hidden = true;
    stopHero();
    return;
  }
  $('heroSlides').replaceChildren(...slides.map((s, i) => slideNode(s, i, slides.length)));
  $('heroTotal').textContent = slides.length;
  $('heroCurrent').textContent = hero.index + 1;
  $('heroControls').hidden = slides.length < 2;
  if (slides.length < 2) stopHero();
}

function showSlide(index) {
  const slides = [...$('heroSlides').children];
  if (slides.length < 2) return;
  hero.index = (index + slides.length) % slides.length;
  slides.forEach((s, i) => { s.hidden = i !== hero.index; });
  $('heroCurrent').textContent = hero.index + 1;
}

function setPlayButton() {
  const button = $('heroPlay');
  button.classList.toggle('playing', hero.playing);
  button.setAttribute('aria-label', hero.playing ? '자동 넘김 멈춤' : '자동 넘김 시작');
  // 자동으로 넘어가는 동안에는 화면 낭독기가 매번 읽지 않게 한다
  $('heroSlides').setAttribute('aria-live', hero.playing ? 'off' : 'polite');
}

function startHero() {
  hero.playing = true;
  clearInterval(hero.timer);
  hero.timer = setInterval(() => { if (!hero.paused) showSlide(hero.index + 1); }, HERO_INTERVAL);
  setPlayButton();
}

function stopHero() {
  hero.playing = false;
  clearInterval(hero.timer);
  setPlayButton();
}

function setupHero() {
  $('heroPrev').addEventListener('click', () => showSlide(hero.index - 1));
  $('heroNext').addEventListener('click', () => showSlide(hero.index + 1));
  $('heroPlay').addEventListener('click', () => (hero.playing ? stopHero() : startHero()));
  // 마우스를 올리거나 키보드 초점이 들어오면 잠시 멈춘다 (읽는 도중 넘어가지 않게)
  const box = $('hero');
  box.addEventListener('mouseenter', () => { hero.paused = true; });
  box.addEventListener('mouseleave', () => { hero.paused = false; });
  box.addEventListener('focusin', () => { hero.paused = true; });
  box.addEventListener('focusout', (e) => { if (!box.contains(e.relatedTarget)) hero.paused = false; });
  setPlayButton();
}

// ================================================================ ③ 내 주변 백년가게

let storeCategory = '';
let storeRequest = 0;

function storeCard(s) {
  const verified = s.infoStatus !== 'UNVERIFIED';
  return el('li', {},
    el('a', { href: `/store.html?id=${s.id}`, class: 'store-card' },
      el('span', { class: `store-art art-${CATEGORY_ICON[s.category] ?? 'store'}`, 'aria-hidden': 'true' }, icon(ICONS[CATEGORY_ICON[s.category] ?? 'store'])),
      el('span', { class: 'card-body' },
        el('span', { class: 'card-meta' }, el('span', { text: s.category ?? '백년가게' }), el('b', { text: formatDistance(s.distanceM) })),
        el('strong', { class: 'card-title', text: s.name }),
        el('span', { class: 'card-sub', text: s.roadAddress ?? '' }),
        el('span', { class: `info-badge ${verified ? 'info-ok' : 'info-unknown'}` },
          icon(verified ? ICONS.check : ICONS.info), verified ? '영업 확인됨' : '영업 정보 미확인'))));
}

async function loadStores(place) {
  const mine = ++storeRequest;
  $('storesStatus').textContent = '불러오는 중...';
  $('storeList').replaceChildren();
  const where = { lat: place.lat.toFixed(6), lng: place.lng.toFixed(6), categories: storeCategory ? [storeCategory] : [] };
  try {
    let radius = 1000;
    let stores = await api.nearbyStores({ ...where, radius });
    if (!stores.length) {
      radius = 3000;
      stores = await api.nearbyStores({ ...where, radius });
    }
    if (mine !== storeRequest) return;   // 기다리는 사이 다른 탭을 눌렀으면 버린다
    if (!storeCategory) { latest.stores = stores; latest.storeRadius = radius; }
    $('storeList').replaceChildren(...stores.slice(0, 4).map(storeCard));
    $('storeList').hidden = !stores.length;
    const what = storeCategory ? `${storeCategory} 백년가게` : '백년가게';
    $('storesStatus').textContent = stores.length
      ? (radius > 1000 ? `1km 안에는 없어서 3km 안의 ${what}를 보여 드려요.` : '')
      : `3km 안에 ${what}가 없어요. 위치를 바꾸거나 다른 분류를 골라 보세요.`;
  } catch (_) {
    if (mine === storeRequest) $('storesStatus').textContent = '가게를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
  updateMapLinks();
}

function setupStoreTabs() {
  const tabs = [...document.querySelectorAll('#storeTabs [role="tab"]')];
  const select = (tab, focus) => {
    tabs.forEach((t) => {
      const on = t === tab;
      t.setAttribute('aria-selected', String(on));
      t.tabIndex = on ? 0 : -1;
    });
    if (focus) tab.focus();
    $('storePanel').setAttribute('aria-labelledby', tab.id);
    storeCategory = tab.dataset.category;
    if (latest.place) loadStores(latest.place);
  };
  tabs.forEach((tab, i) => {
    tab.addEventListener('click', () => select(tab, false));
    tab.addEventListener('keydown', (event) => {
      const next = { ArrowRight: i + 1, ArrowLeft: i - 1, Home: 0, End: tabs.length - 1 }[event.key];
      if (next === undefined) return;
      event.preventDefault();
      select(tabs[(next + tabs.length) % tabs.length], true);
    });
  });
}

// ================================================================ ④ 이번 주 근처 축제

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
      class: `week-day${day === today ? ' today' : ''}`,
      'aria-label': label,
      'aria-current': day === today ? 'date' : null,
    },
    el('span', { class: 'wd', text: WEEKDAYS[weekday(day)] }),
    el('span', { class: 'num', text: String(Number(day.slice(8))) }),
    el('span', { class: count ? 'dot' : 'dot none' }));
    return el('li', {}, link);
  }));
}

function festivalCard(f) {
  const s = status(f);
  return el('li', {},
    el('a', { href: `/festival.html?id=${f.id}`, class: 'festival-card' },
      // 관광공사 사진은 '변경금지' 조건이 있을 수 있어 자르지 않고 비율 그대로 보여 준다
      f.imageUrl
        ? el('span', { class: 'festival-photo' },
          el('img', { src: f.imageUrl, alt: '', loading: 'lazy', referrerpolicy: 'no-referrer' }))
        : el('span', { class: 'festival-art', 'aria-hidden': 'true' }, icon(ICONS.festival)),
      el('span', { class: 'card-body' },
        el('span', { class: `status-badge ${s.cls}` }, icon(ICONS.clock), s.text),
        el('strong', { class: 'card-title', text: f.name }),
        el('span', { class: 'card-sub', text: periodText(f) }),
        el('span', { class: 'card-meta' },
          el('span', { class: 'with-icon' }, icon(ICONS.pin), f.regionName ?? f.place ?? ''),
          el('b', { text: formatDistance(f.distanceM) })),
        f.imageUrl ? el('span', { class: 'credit', text: photoCredit(f.imageLicense) }) : null)));
}

async function loadFestivals(place) {
  const days = thisWeek();
  $('festivalsStatus').textContent = '불러오는 중...';
  $('festivalList').replaceChildren();
  try {
    const festivals = await api.festivalsNearby({
      lat: place.lat.toFixed(6), lng: place.lng.toFixed(6), from: days[0], to: days[6], radius: FESTIVAL_RADIUS,
    });
    // 짧은 축제 먼저, 몇 달씩 이어지는 행사는 뒤로
    const sorted = [...festivals.filter((f) => !f.longTerm), ...festivals.filter((f) => f.longTerm)];
    latest.festivals = sorted;
    renderWeek(days, festivals);
    $('festivalList').replaceChildren(...sorted.slice(0, 3).map(festivalCard));
    $('festivalList').hidden = !festivals.length;
    $('festivalsStatus').textContent = festivals.length
      ? (festivals.length > 3 ? `${FESTIVAL_RADIUS / 1000}km 안 ${festivals.length}개 중 3개 · 나머지는 축제 달력에서 볼 수 있어요.` : '')
      : `이번 주 ${FESTIVAL_RADIUS / 1000}km 안에는 등록된 축제가 없어요. 축제 달력에서 다른 지역도 볼 수 있어요.`;
  } catch (_) {
    latest.festivals = [];
    renderWeek(days, []);
    $('festivalList').hidden = true;
    $('festivalsStatus').textContent = '축제를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
}

// ================================================================ ⑤ 오늘 장 서는 시장 + 5일장 달력

function marketItem(m, isFair) {
  return el('li', {},
    el('a', { href: `/market.html?id=${m.id}`, class: 'market-item' },
      el('span', { class: 'badge-market', text: '전통시장' }),
      el('span', { class: 'market-text' },
        el('strong', { class: 'card-title', text: m.name }),
        // marketDaySummary가 오늘 장날이면 '오늘 장 서요'까지 알려 준다. 그 부분만 굵게
        fairSummary(m, isFair)),
      el('b', { class: 'distance', text: formatDistance(m.distanceM) }),
      icon(ICONS.chevron, 'go')));
}

function fairSummary(m, isFair) {
  const text = marketDaySummary(m);
  const mark = ' · 오늘 장 서요';
  if (!isFair || !text.endsWith(mark)) return el('span', { class: 'card-sub', text });
  return el('span', { class: 'card-sub' }, text.slice(0, -mark.length), ' · ', el('b', { class: 'fair-today', text: '오늘 장 서요' }));
}

const fair = { market: null, year: 0, month: 0 };

function renderFairCalendar() {
  const m = fair.market;
  // 근처에 정기장(5일장 등)이 없으면 달력을 숨기고 시장 목록을 넓게
  document.querySelector('.market-layout').classList.toggle('no-calendar', !m);
  if (!m) {
    $('fairCalendar').hidden = true;
    return;
  }
  const digits = digitsOf(m.marketDays);
  const today = todayString();
  const { year, month } = fair;
  $('fairTitle').textContent = `${year}년 ${month}월`;
  $('fairMarket').replaceChildren(el('a', { href: `/market.html?id=${m.id}`, text: m.name }), ` 장날 · ${cycleLabel(digits)}`);
  $('fairCaption').textContent = `${m.name} ${year}년 ${month}월 장날 달력`;
  const first = weekday(ymd(year, month, 1));
  const total = daysIn(year, month);
  const cells = [...Array(first).fill(null), ...Array.from({ length: total }, (_, i) => i + 1)];
  while (cells.length % 7) cells.push(null);
  const rows = [];
  for (let i = 0; i < cells.length; i += 7) {
    rows.push(el('tr', {}, cells.slice(i, i + 7).map((d) => {
      if (!d) return el('td', {});
      const date = ymd(year, month, d);
      const on = isMarketDay(digits, date);
      return el('td', { class: [on ? 'on' : '', date === today ? 'today' : ''].filter(Boolean).join(' ') || null },
        el('span', { class: 'day', text: String(d) }),
        on ? el('span', { class: 'sr-only', text: ' 장날' }) : null,
        date === today ? el('span', { class: 'sr-only', text: ' 오늘' }) : null);
    })));
  }
  $('fairBody').replaceChildren(...rows);
  $('fairCalendar').hidden = false;
}

function moveFairMonth(step) {
  const t = new Date(Date.UTC(fair.year, fair.month - 1 + step, 1));
  fair.year = t.getUTCFullYear();
  fair.month = t.getUTCMonth() + 1;
  renderFairCalendar();
}

async function loadMarkets(place) {
  $('marketsStatus').textContent = '불러오는 중...';
  $('marketList').replaceChildren();
  try {
    const markets = await api.nearbyMarkets({ lat: place.lat.toFixed(6), lng: place.lng.toFixed(6), radius: MARKET_RADIUS });
    const today = todayString();
    // 오늘 정기장이 서는 시장을 먼저, 나머지는 가까운 순
    const fairs = markets.filter((m) => isMarketDay(digitsOf(m.marketDays), today));
    const rest = markets.filter((m) => !fairs.includes(m));
    latest.markets = markets;
    latest.fair = fairs;
    $('marketsTitle').textContent = fairs.length ? '오늘 장 서는 시장' : '가까운 전통시장';
    $('marketList').replaceChildren(...[...fairs, ...rest].slice(0, 4).map((m) => marketItem(m, fairs.includes(m))));
    $('marketsStatus').textContent = markets.length
      ? (fairs.length ? `오늘 장이 서는 시장 ${fairs.length}곳 · ${MARKET_RADIUS / 1000}km 안` : `${MARKET_RADIUS / 1000}km 안 · 오늘 정기장이 서는 시장은 없어요`)
      : `${MARKET_RADIUS / 1000}km 안에 전통시장이 없어요. 위치를 바꿔 보세요.`;
    // 달력: 가장 가까운 정기장(5일장 등)
    fair.market = markets.find((m) => digitsOf(m.marketDays).length) ?? null;
    [fair.year, fair.month] = today.split('-').map(Number);
    renderFairCalendar();
  } catch (_) {
    latest.markets = [];
    latest.fair = [];
    fair.market = null;
    renderFairCalendar();
    $('marketsStatus').textContent = '시장을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
}

// ================================================================ ⑤-1 테마로 걷기

function themeCount(t) {
  if (t.id === 'fair-markets') {
    const near = `30km 안 ${t.near.toLocaleString()}${t.unit}`;
    return t.today ? `${near} · 오늘 장 서는 곳 전국 ${t.today.toLocaleString()}${t.unit}` : near;
  }
  return `30km 안 ${t.near.toLocaleString()}${t.unit} · 전국 ${t.total.toLocaleString()}${t.unit}`;
}

async function loadThemes(place) {
  $('themesStatus').textContent = '불러오는 중...';
  try {
    const res = await fetch(`/api/themes?lat=${place.lat.toFixed(6)}&lng=${place.lng.toFixed(6)}`);
    if (!res.ok) throw new Error();
    const themes = await res.json();
    $('themeCards').replaceChildren(...themes.map((t, i) => el('li', { class: i === 0 ? 'theme-big' : 'theme-small' },
      el('a', { class: 'theme-tile', href: `/theme.html?id=${t.id}&${placeLinkQuery(place)}`, 'data-theme': t.id },
        el('span', { class: 'theme-tile-icon' }, icon(THEME_ICONS[t.id])),
        el('span', { class: 'theme-tile-title', text: t.title }),
        el('span', { class: 'theme-tile-desc', text: t.description }),
        el('span', { class: 'theme-tile-count', text: themeCount(t) })))));
    $('themesStatus').textContent = `${placeText(place)} 30km 안의 수예요.`;
  } catch (_) {
    $('themeCards').replaceChildren();
    $('themesStatus').textContent = '테마를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
}

// ================================================================ ⑤-0 주변 관광지와 걷기 코스 (TOUR-01)

const TOUR_RADIUS = 2000;
let tourRequest = 0;

function courseLink(lat, lng, label) {
  return `/course.html?${new URLSearchParams({ lat: Number(lat).toFixed(6), lng: Number(lng).toFixed(6), label, from: 'home' })}`;
}

function tourCard(t) {
  return el('li', {},
    el('a', { href: courseLink(t.latitude, t.longitude, t.title), class: 'festival-card tour-card' },
      // 관광공사 사진은 '변경금지' 조건이 있을 수 있어 자르지 않고 비율 그대로 보여 준다
      t.imageUrl
        ? el('span', { class: 'festival-photo' }, el('img', { src: t.imageUrl, alt: '', loading: 'lazy', referrerpolicy: 'no-referrer' }))
        : el('span', { class: 'festival-art', 'aria-hidden': 'true' }, icon(ICONS.pin)),
      el('span', { class: 'card-body' },
        el('span', { class: 'card-meta' }, el('span', { class: 'tour-type', text: t.typeName }), el('b', { text: formatDistance(t.distanceM) })),
        el('strong', { class: 'card-title', text: t.title }),
        t.address ? el('span', { class: 'card-sub', text: t.address }) : null,
        el('span', { class: 'tour-go', text: '여기서 걷기 코스 만들기' }),
        t.imageUrl ? el('span', { class: 'credit', text: photoCredit(t.imageLicense) }) : null)));
}

async function loadTour(place) {
  const mine = ++tourRequest;
  $('tourCourseLink').href = courseLink(place.lat, place.lng, place.label);
  $('tourCourseText').textContent = `${place.label === '내 주변' ? '내 위치' : place.label}에서 코스 만들기`;
  $('tourStatus').textContent = '불러오는 중...';
  $('tourList').replaceChildren();
  $('tourList').hidden = true;
  try {
    const res = await fetch(`/api/tour/nearby?lat=${place.lat.toFixed(6)}&lng=${place.lng.toFixed(6)}&radius=${TOUR_RADIUS}`);
    if (!res.ok) throw new Error();
    const data = await res.json();
    if (mine !== tourRequest) return;
    const spots = data.spots.slice(0, 8);
    $('tourList').replaceChildren(...spots.map(tourCard));
    $('tourList').hidden = !spots.length;
    $('tourStatus').textContent = spots.length
      ? `${placeText(place)} 2km 안 · ${data.source}`
      : data.available
        ? `${placeText(place)} 2km 안에 관광공사에 등록된 관광지가 없어요. 출발할 장소를 찾아 보세요.`
        : '관광지 정보를 지금 불러오지 못했어요. 장소를 찾아 코스를 만들 수는 있어요.';
  } catch (_) {
    if (mine === tourRequest) $('tourStatus').textContent = '관광지 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
}

// ================================================================ ⑥ 공지사항

async function loadNotices() {
  $('noticesStatus').textContent = '불러오는 중...';
  try {
    const res = await fetch('/api/notices?size=4');
    if (!res.ok) throw new Error();
    const data = await res.json();
    $('noticeList').replaceChildren(...data.items.map((n) => el('li', {},
      el('a', { href: `/notice.html?id=${n.id}`, class: 'notice-row' },
        n.pinned ? el('span', { class: 'pin-badge', text: '고정' }) : null,
        el('span', { class: 'notice-title', text: n.title }),
        el('time', { datetime: n.createdAt.slice(0, 10), text: n.createdAt.slice(0, 10).replaceAll('-', '.') })))));
    $('noticesStatus').textContent = data.items.length ? '' : '아직 공지가 없어요.';
  } catch (_) {
    $('noticesStatus').textContent = '공지를 불러오지 못했어요.';
  }
}

// ================================================================ 그리기

async function show(place) {
  latest.place = place;
  $('placeLabel').textContent = placeText(place);
  $('placeNote').textContent = place.isDefault ? '위치 권한이 없어 기본 위치를 보여 드려요.' : '';
  updateMapLinks();
  loadTour(place);   // 관광공사 응답을 기다리느라 주요 소식이 늦어지지 않게 따로
  await Promise.all([loadStores(place), loadFestivals(place), loadMarkets(place), loadThemes(place)]);
  renderHero();
}

// ================================================================ 위치 바꾸기 창

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

// ================================================================ 시작

async function init() {
  mountAccount($('account'), { next: '/', onLogout: () => toast('로그아웃했어요.') });
  setupNav();
  setupMenuDialog();
  setupSearch();
  setupTextSize();
  setupHero();
  setupStoreTabs();
  loadNotices();   // 위치와 상관없으므로 먼저
  if (await handleLoginReturn((message) => toast(message))) return;   // 카카오 로그인 후 보던 화면으로 이동

  $('placeButton').addEventListener('click', openPlaceDialog);
  $('sidoSelect').addEventListener('change', fillSigungu);
  $('placeDialog').addEventListener('close', onPlaceChosen);
  $('useMyLocation').addEventListener('click', useMyLocationFromDialog);
  $('fairPrev').addEventListener('click', () => moveFairMonth(-1));
  $('fairNext').addEventListener('click', () => moveFairMonth(1));

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
