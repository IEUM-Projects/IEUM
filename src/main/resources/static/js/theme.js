// 테마 한 가지의 목록 (THM-01): 백년가게 골목 / 시장 안 백년가게 / 5일장
import { mountAccount } from './header.js';
import * as auth from './auth.js';
import { marketDaySummary } from './market-days.js';
import { distText, homePlace, moveText, placeLinkQuery, placeQuery, placeText } from './theme-common.js';

const $ = (id) => document.getElementById(id);
const THEMES = ['alleys', 'market-stores', 'fair-markets'];
const NOTES = {
  alleys: '150m 안에 이어진 백년가게가 3곳 이상인 곳을 골목으로 묶었어요. 이름은 가까운 시장이나 가장 많은 도로 이름으로 붙였어요.',
  'market-stores': '시장 위치에서 300m 안에 있는 백년가게를 보여 드려요. 시장 안이 아닌 가까운 가게가 섞일 수 있어요.',
  'fair-markets': '장날은 날짜 끝자리로 계산해요 (31일은 시장마다 달라서 빼요). 가시기 전에 시장에 한 번 확인해 주세요.',
};

const params = new URLSearchParams(location.search);
const id = params.get('id');
const place = homePlace();
const RADII = ['2000', '10000', '30000', 'all'];
// radius: 고른 거리 ('auto' 는 2km → 10km → 30km 순으로 넓혀 처음 있는 반경). shown: 서버가 실제로 쓴 반경 (더 보기 때 그대로)
const state = { page: 0, today: params.get('today') === '1', loading: false, radius: RADII.includes(params.get('radius')) ? params.get('radius') : 'auto', shown: null };

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([key, value]) => {
    if (value === null || value === undefined || value === false) return;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value);
  });
  children.flat().filter((c) => c !== null && c !== undefined && c !== false && c !== '').forEach((child) => node.append(child));
  return node;
}

function storeChips(stores, label) {
  return el('ul', { class: 'store-chips', 'aria-label': label },
    stores.map((s) => el('li', {}, el('a', { class: 'store-chip', href: `/store.html?id=${s.id}` },
      el('span', { text: s.name }),
      s.category ? el('span', { class: 'chip-sub', text: s.category }) : null))));
}

function side(distanceM) {
  return distanceM === null || distanceM === undefined ? null
    : el('span', { class: 'item-side', text: moveText(distanceM), 'aria-label': `여기서 ${moveText(distanceM).replace(/^여기서 /, '')}` });
}

function alleyItem(a) {
  const map = `/map.html?lat=${a.centerLat.toFixed(6)}&lng=${a.centerLng.toFixed(6)}&label=${encodeURIComponent(a.label)}&show=store&radius=500`;
  return el('li', { class: 'theme-item' },
    el('div', { class: 'item-head' },
      el('h2', { class: 'item-title' }, el('a', { href: map, text: a.label })),
      side(a.distanceM)),
    el('p', { class: 'item-meta', text: `${a.regionName ?? ''} · 백년가게 ${a.stores.length}곳 · 끝에서 끝까지 약 ${distText(a.spanM)}` }),
    storeChips(a.stores, `${a.label}의 백년가게`),
    el('a', { class: 'item-link', href: map, text: '지도에서 골목 보기' }));
}

function fairBadge(m) {
  return m.fairToday ? el('span', { class: 'badge-today', text: '오늘 장날' }) : null;
}

function marketStoresItem(ms) {
  const m = ms.market;
  return el('li', { class: 'theme-item' },
    el('div', { class: 'item-head' },
      el('h2', { class: 'item-title' }, el('a', { href: `/market.html?id=${m.id}`, text: m.name }), fairBadge(m)),
      side(m.distanceM)),
    el('p', { class: 'item-meta', text: `${m.regionName ?? ''} · ${marketDaySummary(m)} · 백년가게 ${ms.stores.length}곳` }),
    storeChips(ms.stores, `${m.name} 근처 백년가게`));
}

function fairItem(m) {
  return el('li', { class: 'theme-item compact' },
    el('a', { class: 'item-row', href: `/market.html?id=${m.id}` },
      el('span', { class: 'item-main' },
        el('span', { class: 'item-title-text' }, el('span', { text: m.name }), fairBadge(m)),
        el('span', { class: 'item-meta', text: `${m.regionName ?? ''} · ${marketDaySummary(m)}` })),
      side(m.distanceM)));
}

const RENDER = { alleys: alleyItem, 'market-stores': marketStoresItem, 'fair-markets': fairItem };

function summaryText(t, total, radius) {
  const where = place ? `${placeText(place)} 가까운 순` : (id === 'fair-markets' ? '지역 순' : '가게가 많은 순');
  const near = place && radius ? `${distText(radius)} 안` : '';
  const scope = state.today ? [near, '오늘 장 서는 곳'].filter(Boolean).join(' ') : (near || '전국');
  return `${scope} ${total.toLocaleString()}${t.unit} · ${where}`;
}

/** 거리 칩: 서버가 실제로 쓴 반경을 눌린 상태로 (전국 = null) */
function renderRadius(data) {
  const shown = data.radius ? String(data.radius) : 'all';
  document.querySelectorAll('#radiusChips [data-radius]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.radius === shown)));
  if (!data.expanded) $('radiusNote').textContent = '';
  else if (data.radius) $('radiusNote').textContent = `걸어서 2km 안에는 없어서 ${distText(data.radius)} 안을 보여 드려요.`;
  else $('radiusNote').textContent = '30km 안에는 없어서 전국에서 가까운 순으로 보여 드려요.';
}

async function load(append) {
  if (state.loading) return;
  state.loading = true;
  $('moreButton').disabled = true;
  if (!append) $('summary').textContent = '불러오는 중...';
  try {
    // 더 보기는 첫 쪽에서 정해진 반경 그대로 (auto 로 다시 물으면 바뀔 수 있음)
    const radius = place ? (append && state.shown ? state.shown : state.radius) : '';
    const qs = [placeQuery(place), `page=${state.page}`, state.today ? 'today=true' : '', radius ? `radius=${radius}` : ''].filter(Boolean).join('&');
    const res = await fetch(`/api/themes/${id}?${qs}`);
    if (!res.ok) throw new Error();
    const data = await res.json();
    const t = data.theme;
    document.title = `${t.title} - 테마로 걷기 - 이음`;
    $('themeTitle').textContent = t.title;
    $('themeDesc').textContent = t.description;
    const nodes = data.items.map(RENDER[id]);
    if (append) $('list').append(...nodes);
    else $('list').replaceChildren(...nodes);
    if (!append) state.shown = data.radius ? String(data.radius) : 'all';
    if (place) renderRadius(data);
    $('summary').textContent = data.total ? summaryText(t, data.total, data.radius)
      : (state.today ? `${data.radius ? `${distText(data.radius)} 안에는 ` : ''}오늘 장 서는 곳이 없어요.` : `${data.radius ? `${distText(data.radius)} 안에는 ` : ''}없어요. 거리를 넓혀 보세요.`);
    $('moreButton').hidden = !data.hasNext;
    if (append && nodes.length) nodes[0].querySelector('a').focus();
  } catch (_) {
    $('summary').textContent = '목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  } finally {
    state.loading = false;
    $('moreButton').disabled = false;
  }
}

function init() {
  mountAccount($('account'));
  auth.init();
  if (!THEMES.includes(id)) {
    $('themeTitle').textContent = '없는 테마예요';
    $('themeDesc').replaceChildren('주소를 다시 확인하시거나 ', el('a', { href: '/themes.html', text: '테마 목록' }), '에서 골라 주세요.');
    return;
  }
  $('themeNote').textContent = NOTES[id];
  if (id === 'fair-markets') {
    const button = $('todayFilter');
    button.hidden = false;
    button.setAttribute('aria-pressed', String(state.today));
    button.addEventListener('click', () => {
      state.today = !state.today;
      state.page = 0;
      button.setAttribute('aria-pressed', String(state.today));
      const url = new URL(location.href);
      if (state.today) url.searchParams.set('today', '1'); else url.searchParams.delete('today');
      history.replaceState(null, '', url);
      load(false);
    });
  }
  if (place) {
    document.querySelector('.back-link').href = `/themes.html?${placeLinkQuery(place)}`;
    $('radiusChips').hidden = false;
    $('radiusChips').addEventListener('click', (event) => {
      const b = event.target.closest('[data-radius]');
      if (!b) return;
      state.radius = b.dataset.radius;
      state.page = 0;
      const url = new URL(location.href);
      url.searchParams.set('radius', state.radius);
      history.replaceState(null, '', url);
      load(false);
    });
  }
  $('moreButton').addEventListener('click', () => { state.page += 1; load(true); });
  load(false);
}

init();
