// 통합 검색 화면 (SRC-01): /search.html?q=전주
// 기준 위치: 홈에서 고른 지역이 있으면 그 위치로 가까운 순 (이 화면에서는 위치 권한을 따로 묻지 않음)
import { mountAccount } from './header.js';
import * as auth from './auth.js';
import { formatDistance } from './api.js';
import { periodText, status } from './festival-format.js';
import { marketDaySummary } from './market-days.js';
import { addRecent, clearRecent, cleanQuery, highlight, recentSearches, removeRecent, SEARCH_EXAMPLES } from './search-common.js';

const $ = (id) => document.getElementById(id);
const state = { q: '', words: [], where: null, pages: { stores: 0, markets: 0, festivals: 0 } };

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([key, value]) => {
    if (value === null || value === undefined || value === false) return;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value);
  });
  children.flat().filter((c) => c !== null && c !== undefined && c !== false).forEach((child) => node.append(child));
  return node;
}

/** 홈에서 고른 지역 (없으면 null → 이름 순) */
function homePlace() {
  try {
    const p = JSON.parse(localStorage.getItem('ieum_home_place'));
    return p && typeof p.lat === 'number' && typeof p.lng === 'number' ? p : null;
  } catch (_) {
    return null;
  }
}

async function getJson(url) {
  const res = await fetch(url);
  const body = await res.json().catch(() => null);
  if (!res.ok) throw Object.assign(new Error(body?.details?.[0] ?? body?.message ?? '검색하지 못했어요.'), { status: res.status });
  return body;
}

function params(extra = {}) {
  const p = new URLSearchParams({ q: state.q, ...extra });
  if (state.where) { p.set('lat', state.where.lat.toFixed(6)); p.set('lng', state.where.lng.toFixed(6)); }
  return p;
}

// ---------------------------------------------------------------- 결과 그리기

const mark = (text) => highlight(text, state.words);

function storeItem(s) {
  return el('li', {},
    el('a', { href: `/store.html?id=${s.id}`, class: 'result-item' },
      el('span', { class: 'result-main' },
        el('strong', { class: 'result-name' }, mark(s.name)),
        s.type === 'CREATOR' ? el('span', { class: 'badge-type creator', text: '로컬크리에이터' }) : null,
        s.infoStatus === 'UNVERIFIED' ? el('span', { class: 'badge-unverified', text: s.type === 'CREATOR' ? '운영 여부 미확인' : '영업 정보 미확인' }) : null),
      el('span', { class: 'result-meta' }, s.category ? [...mark(s.category), ' · '] : null, mark(s.roadAddress ?? '')),
      s.distanceM !== null ? el('span', { class: 'result-side', text: formatDistance(s.distanceM) }) : null));
}

function marketItem(m) {
  const goodsHit = m.goods && state.words.some((w) => m.goods.toLowerCase().includes(w.toLowerCase()));
  return el('li', {},
    el('a', { href: `/market.html?id=${m.id}`, class: 'result-item' },
      el('span', { class: 'result-main' },
        el('span', { class: 'badge-market', text: '전통시장' }),
        el('strong', { class: 'result-name' }, mark(m.name))),
      el('span', { class: 'result-meta' }, marketDaySummary(m), m.roadAddress ? [' · ', ...mark(m.roadAddress)] : null),
      goodsHit ? el('span', { class: 'result-meta goods' }, '주요 상품: ', mark(m.goods)) : null,
      m.distanceM !== null ? el('span', { class: 'result-side', text: formatDistance(m.distanceM) }) : null));
}

function festivalItem(f) {
  const s = status(f);
  return el('li', {},
    el('a', { href: `/festival.html?id=${f.id}`, class: 'result-item' },
      el('span', { class: 'result-main' },
        el('span', { class: `status ${s.cls}`, text: s.text }),
        el('strong', { class: 'result-name' }, mark(f.name))),
      el('span', { class: 'result-meta' }, periodText(f), f.place ? [' · ', ...mark(f.place)] : null,
        f.regionName ? ` · ${f.regionName}` : null),
      f.distanceM !== null ? el('span', { class: 'result-side', text: formatDistance(f.distanceM) }) : null));
}

const ITEM = { stores: storeItem, markets: marketItem, festivals: festivalItem };
const IDS = { stores: 'store', markets: 'market', festivals: 'festival' };

function fill(type, group) {
  const id = IDS[type];
  $(`${id}Section`).hidden = group.total === 0;
  $(`${id}Count`).textContent = `${group.total.toLocaleString()}건`;
  $(`${id}List`).replaceChildren(...group.items.map(ITEM[type]));
  $(`${id}More`).hidden = group.total <= group.items.length;
}

async function more(type) {
  const button = $(`${IDS[type]}More`);
  button.disabled = true;
  try {
    // 처음 5개를 보여 준 뒤에는 20개씩: 0쪽부터 다시 받아 5개 뒤를 붙인다
    const shown = $(`${IDS[type]}List`).children.length;
    const page = shown <= 5 ? 0 : state.pages[type] + 1;
    const data = await getJson(`/api/search/${type}?${params({ page })}`);
    state.pages[type] = page;
    const fresh = page === 0 ? data.items.slice(shown) : data.items;
    const nodes = fresh.map(ITEM[type]);
    $(`${IDS[type]}List`).append(...nodes);
    button.hidden = !data.hasNext;
    nodes[0]?.querySelector('a')?.focus();   // 새로 붙은 첫 결과로
  } catch (e) {
    button.textContent = '다시 시도';
  } finally {
    button.disabled = false;
  }
}

async function run(q) {
  state.q = q;
  state.pages = { stores: 0, markets: 0, festivals: 0 };
  document.title = `${q} - 검색 - 이음`;
  $('start').hidden = true;
  $('results').hidden = false;
  $('summary').textContent = '찾는 중...';
  ['region', 'store', 'market', 'festival'].forEach((id) => { $(`${id}Section`).hidden = true; });
  $('noResult').hidden = true;
  try {
    const data = await getJson(`/api/search?${params()}`);
    state.words = data.words;
    addRecent(data.query);
    $('regionList').replaceChildren(...data.regions.map((r) => el('li', {},
      el('a', { class: 'chip-link', href: `/map.html?${new URLSearchParams({ lat: r.centerLat.toFixed(6), lng: r.centerLng.toFixed(6), label: r.label })}` },
        el('span', {}, mark(r.label), el('span', { class: 'chip-count', text: ` · 백년가게 ${r.storeCount}곳` }))))));
    $('regionSection').hidden = !data.regions.length;
    fill('stores', data.stores);
    fill('markets', data.markets);
    fill('festivals', data.festivals);
    const total = data.stores.total + data.markets.total + data.festivals.total;
    const where = state.where ? ` · ${state.where.label === '내 주변' ? '내 주변' : `${state.where.label} 기준`} 가까운 순` : '';
    $('summary').textContent = total || data.regions.length
      ? `‘${data.query}’ 검색 결과 ${total.toLocaleString()}건${data.regions.length ? `, 지역 ${data.regions.length}곳` : ''}${where}`
      : `‘${data.query}’에 맞는 결과가 없어요.`;
    $('noResult').hidden = Boolean(total || data.regions.length);
  } catch (e) {
    $('summary').textContent = e.message;
  }
}

// ---------------------------------------------------------------- 검색어 없을 때

function chip(text, onClick) {
  const a = el('a', { class: 'chip-link', href: `/search.html?q=${encodeURIComponent(text)}`, text });
  if (onClick) a.addEventListener('click', onClick);
  return a;
}

function renderStart() {
  const recent = recentSearches();
  $('recentList').replaceChildren(...recent.map((q) => {
    const remove = el('button', { type: 'button', class: 'chip-remove', 'aria-label': `최근 검색어 ${q} 지우기` }, '×');
    remove.addEventListener('click', () => { removeRecent(q); renderStart(); $('q').focus(); });
    return el('li', { class: 'chip-with-remove' }, chip(q), remove);
  }));
  $('recentEmpty').hidden = recent.length > 0;
  $('clearRecent').hidden = recent.length === 0;
  $('exampleList').replaceChildren(...SEARCH_EXAMPLES.map((q) => el('li', {}, chip(q))));
  $('start').hidden = false;
  $('results').hidden = true;
}

// ---------------------------------------------------------------- 시작

function init() {
  mountAccount($('account'));
  auth.init();
  state.where = homePlace();
  $('clearRecent').addEventListener('click', () => { clearRecent(); renderStart(); $('q').focus(); });
  ['stores', 'markets', 'festivals'].forEach((t) => $(`${IDS[t]}More`).addEventListener('click', () => more(t)));
  $('searchForm').addEventListener('submit', (event) => {
    const q = cleanQuery($('q').value);
    if (!q) {
      event.preventDefault();
      $('q').focus();
      return;
    }
    $('q').value = q;   // 폼이 /search.html?q=... 로 이동 (뒤로 가기로 이전 검색으로 돌아갈 수 있게)
  });
  const q = cleanQuery(new URLSearchParams(location.search).get('q'));
  $('q').value = q;
  if (q) run(q);
  else { renderStart(); $('q').focus(); }
}

init();
