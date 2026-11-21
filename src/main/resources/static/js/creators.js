// 로컬크리에이터 모아 보기 (LOC-01): 지역(시도·시군구)과 분야로 그 지역 전체를 보여 준다 (반경과 상관없이)
import * as auth from './auth.js';
import { mountAccount } from './header.js';

const $ = (id) => document.getElementById(id);
const FIELDS = ['로컬푸드', '지역기반제조', '지역가치', '거점브랜드', '지역특화관광', '자연친화활동', '디지털문화체험'];
const state = { regions: [], sido: '', sigungu: '', field: '', page: 0, shown: 0 };
let request = 0;

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

function where() {
  return [state.sido || '전국', state.sigungu].filter(Boolean).join(' ');
}

function syncAddress() {
  const params = new URLSearchParams();
  if (state.sido) params.set('sido', state.sido);
  if (state.sigungu) params.set('sigungu', state.sigungu);
  if (state.field) params.set('field', state.field);
  history.replaceState(null, '', `/creators.html${params.toString() ? `?${params}` : ''}`);
}

function mapLink(c) {
  const params = new URLSearchParams({ lat: Number(c.latitude).toFixed(6), lng: Number(c.longitude).toFixed(6), label: c.name, show: 'store', category: '로컬크리에이터' });
  return `/map.html?${params}`;
}

function card(c) {
  return el('li', { class: 'creator-card' },
    el('h2', { class: 'creator-name' }, el('a', { href: `/store.html?id=${c.id}`, text: c.name })),
    c.fields.length ? el('p', { class: 'creator-fields' }, ...c.fields.map((f) => el('span', { class: 'badge-field', text: f }))) : null,
    c.item ? el('p', { class: 'creator-item', text: c.item }) : null,
    el('p', { class: 'creator-meta', text: [`${c.latestYear}년 선정${c.years.length > 1 ? ` (${c.years.length}회: ${c.years.join('·')})` : ''}`, c.roadAddress].filter(Boolean).join(' · ') }),
    el('p', { class: 'creator-actions' },
      el('a', { href: `/store.html?id=${c.id}`, class: 'button-text', 'aria-label': `${c.name} 자세히 보기`, text: '자세히 보기' }),
      c.mapped
        ? el('a', { href: mapLink(c), class: 'button-text', 'aria-label': `${c.name} 지도에서 보기`, text: '지도에서 보기' })
        : el('span', { class: 'creator-nomap', text: '지도 위치 확인 중' })));
}

async function load(append = false) {
  const mine = ++request;
  if (!append) {
    state.page = 0;
    state.shown = 0;
    $('creatorList').replaceChildren();
  }
  $('creatorStatus').textContent = '불러오는 중...';
  $('moreButton').hidden = true;
  const params = new URLSearchParams({ sido: state.sido, sigungu: state.sigungu, field: state.field, page: state.page });
  try {
    const res = await fetch(`/api/creators?${params}`);
    if (!res.ok) throw new Error();
    const data = await res.json();
    if (mine !== request) return;
    const cards = data.items.map(card);
    $('creatorList').append(...cards);
    state.shown += data.items.length;
    $('creatorStatus').textContent = data.total
      ? `${where()}${state.field ? ` · ${state.field}` : ''} 로컬크리에이터 ${data.total.toLocaleString()}곳${data.total > state.shown ? ` (${state.shown}곳 보는 중)` : ''} · 최근 선정 순`
      : `${where()}${state.field ? `의 ${state.field}` : ''} 로컬크리에이터가 없어요. 다른 지역이나 분야를 골라 보세요.`;
    $('moreButton').hidden = !data.hasNext;
    if (append && cards.length) cards[0].querySelector('a').focus();
  } catch (_) {
    if (mine === request) $('creatorStatus').textContent = '목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
}

function fillSigungu() {
  const group = state.regions.find((r) => r.sido === state.sido);
  const sel = $('sigunguSelect');
  sel.replaceChildren(el('option', { value: '', text: group ? `전체 (${group.count})` : '전체' }),
    ...(group?.sigungus ?? []).map((g) => el('option', { value: g.name, text: `${g.name} (${g.count})` })));
  sel.disabled = !group || group.sigungus.length === 0;
  sel.value = state.sigungu;
}

function renderFields() {
  $('fieldChips').replaceChildren(...['', ...FIELDS].map((f) => {
    const b = el('button', { type: 'button', class: 'chip', 'aria-pressed': String(state.field === f), text: f || '전체 분야' });
    b.addEventListener('click', () => {
      state.field = f;
      renderFields();
      syncAddress();
      load();
    });
    return b;
  }));
}

async function init() {
  mountAccount($('account'));
  auth.init();
  $('creatorNote').textContent = '출처: 중소벤처기업부 로컬크리에이터 리스트 (2026-09-16 기준, 공공데이터포털). 지역은 주소로 나눴어요. 아이템은 선정 당시 내용이에요.';
  const params = new URLSearchParams(location.search);
  try {
    const res = await fetch('/api/creators/regions');
    if (!res.ok) throw new Error();
    state.regions = await res.json();
  } catch (_) {
    $('creatorStatus').textContent = '지역 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
    return;
  }
  const total = state.regions.reduce((n, r) => n + r.count, 0);
  $('sidoSelect').replaceChildren(el('option', { value: '', text: `전국 (${total.toLocaleString()})` }),
    ...state.regions.map((r) => el('option', { value: r.sido, text: `${r.sido} (${r.count})` })));
  const sido = params.get('sido') ?? '';
  const group = state.regions.find((r) => r.sido === sido);
  state.sido = group ? sido : '';
  const sigungu = params.get('sigungu') ?? '';
  state.sigungu = group?.sigungus.some((g) => g.name === sigungu) ? sigungu : '';
  state.field = FIELDS.includes(params.get('field')) ? params.get('field') : '';
  $('sidoSelect').value = state.sido;
  fillSigungu();
  renderFields();
  $('sidoSelect').addEventListener('change', () => {
    state.sido = $('sidoSelect').value;
    state.sigungu = '';
    fillSigungu();
    syncAddress();
    load();
  });
  $('sigunguSelect').addEventListener('change', () => {
    state.sigungu = $('sigunguSelect').value;
    syncAddress();
    load();
  });
  $('moreButton').addEventListener('click', () => {
    state.page += 1;
    load(true);
  });
  syncAddress();
  load();
}

init();
