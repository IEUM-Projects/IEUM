// 관리자: 전통시장 시설 정보 연결 확인 (DATA-01)
import * as auth from './auth.js';
import { mountAccount } from './header.js';

const $ = (id) => document.getElementById(id);
const STATUSES = ['PENDING', 'LINKED', 'IGNORED', 'AUTO'];
const LABELS = { PENDING: '확인 대기', LINKED: '직접 이음', IGNORED: '맞는 시장 없음', AUTO: '자동 연결' };
const state = { status: 'PENDING', page: 0 };

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

function toast(message, ms = 5000) {
  $('toast').textContent = message;
  $('toast').hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { $('toast').hidden = true; }, ms);
}

class ApiError extends Error {
  constructor(status, body) {
    super(body?.details?.[0] ?? body?.message ?? '잠시 후 다시 시도해 주세요.');
    this.status = status;
    this.code = body?.code ?? `HTTP_${status}`;
  }
}

async function request(url, options = {}) {
  const res = await auth.authFetch(url, {
    ...options,
    headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
  });
  const body = await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(res.status, body);
  return body;
}

const api = {
  list: (status, page) => request(`/api/admin/market-links?status=${status}&page=${page}`),
  act: (code, body) => request(`/api/admin/market-links/${encodeURIComponent(code)}`, { method: 'POST', body: JSON.stringify(body) }),
};

function showGate(message) {
  $('gate').textContent = message;
  $('gate').hidden = false;
  $('workspace').hidden = true;
}

function handleAuthError(e) {
  if (e.status === 401) { location.replace(auth.loginUrl('/admin-markets.html')); return true; }
  if (e.status === 403) {
    showGate('관리자만 볼 수 있는 화면이에요. 관리자 권한을 받았다면 로그아웃했다가 다시 로그인해 주세요.');
    return true;
  }
  return false;
}

// ---------------------------------------------------------------- 한 줄

function marketLine(label, m) {
  if (!m) return el('p', { class: 'link-market none', text: `${label}: 없음` });
  return el('p', { class: 'link-market' },
    el('span', { class: 'link-label', text: `${label}: ` }),
    el('a', { href: `/market.html?id=${m.id}`, target: '_blank', rel: 'noopener', text: `${m.name} (${m.id}번)`, 'aria-label': `${m.name} ${m.id}번 시장 화면 (새 창)` }),
    el('span', { class: 'link-address', text: ` · ${m.address ?? '주소 없음'}` }));
}

function button(text, label, onClick, cls = 'button-secondary') {
  const b = el('button', { type: 'button', class: cls, text, 'aria-label': label });
  b.addEventListener('click', () => onClick(b));
  return b;
}

function row(r) {
  const actions = el('div', { class: 'row-actions' });
  const other = el('form', { class: 'other-form', id: `form-${r.code}`, hidden: true },
    el('label', { for: `other-${r.code}`, text: '이을 시장 번호' }),
    el('input', { id: `other-${r.code}`, type: 'text', inputmode: 'numeric', autocomplete: 'off', maxlength: '10' }),
    el('button', { type: 'submit', class: 'button-primary', text: '이 번호로 잇기' }));
  other.addEventListener('submit', (event) => {
    event.preventDefault();
    const input = other.querySelector('input');
    if (!/^\d{1,10}$/.test(input.value.trim())) {
      input.setAttribute('aria-invalid', 'true');
      toast('시장 번호는 숫자로 입력해 주세요. (시장 화면 주소의 id= 뒤 숫자)');
      input.focus();
      return;
    }
    input.removeAttribute('aria-invalid');
    act(r, { action: 'LINK', marketId: Number(input.value.trim()) }, other.querySelector('button'));
  });
  if (r.status === 'PENDING') {
    if (r.suggested) actions.append(button('이 시장이 맞아요', `${r.name}: ${r.suggested.name} ${r.suggested.id}번과 잇기`, (b) => act(r, { action: 'LINK' }, b), 'button-primary'));
    const toggle = button('다른 시장과 잇기', `${r.name}: 다른 시장 번호로 잇기`, (b) => {
      other.hidden = !other.hidden;
      b.setAttribute('aria-expanded', String(!other.hidden));
      if (!other.hidden) other.querySelector('input').focus();
    });
    toggle.setAttribute('aria-expanded', 'false');
    toggle.setAttribute('aria-controls', `form-${r.code}`);
    actions.append(toggle);
    actions.append(button('맞는 시장 없음', `${r.name}: 맞는 시장 없음`, (b) => act(r, { action: 'IGNORE' }, b)));
  } else {
    actions.append(button(r.status === 'IGNORED' ? '다시 확인하기' : '연결 풀기', `${r.name}: ${r.status === 'IGNORED' ? '다시 확인하기' : '연결 풀기'}`, (b) => act(r, { action: 'RESET' }, b)));
  }
  return el('li', { class: 'admin-row link-row', 'data-code': r.code },
    el('div', { class: 'report-head' },
      el('span', { class: `status-badge st-link-${r.status.toLowerCase()}`, text: LABELS[r.status] }),
      el('h2', { text: r.name })),
    el('p', { class: 'meta', text: `시장코드 ${r.code} · 원본 ${r.region} · ${r.dataDate} 기준${r.reviewedAt ? ` · ${r.reviewedAt.slice(0, 10)} 처리` : ''}` }),
    el('p', { class: 'link-source' }, el('span', { class: 'link-label', text: '원본 주소: ' }), r.address ?? '없음'),
    r.status === 'PENDING' || r.status === 'IGNORED' ? marketLine('이름이 같은 이음 시장', r.suggested) : marketLine('이어진 이음 시장', r.linked),
    actions, other);
}

// ---------------------------------------------------------------- 목록

function renderCounts(counts) {
  STATUSES.forEach((s) => { $(`count-${s}`).textContent = `${(counts[s] ?? 0).toLocaleString()}`; });
}

async function loadList(page = 0) {
  state.page = page;
  if (page === 0) $('list').replaceChildren();
  $('moreButton').hidden = true;
  $('listStatus').textContent = '불러오는 중...';
  try {
    const data = await api.list(state.status, page);
    renderCounts(data.counts);
    const nodes = data.items.map(row);
    $('list').append(...nodes);
    $('moreButton').hidden = !data.hasNext;
    $('listStatus').textContent = data.total
      ? `${LABELS[state.status]} ${data.total.toLocaleString()}곳`
      : state.status === 'PENDING' ? '확인할 시장이 없어요. 모두 처리했어요.' : `${LABELS[state.status]} 시장이 없어요.`;
    if (page > 0 && nodes.length) nodes[0].querySelector('button')?.focus();
  } catch (e) {
    if (!handleAuthError(e)) $('listStatus').textContent = '목록을 불러오지 못했어요.';
  }
}

async function act(r, body, trigger) {
  trigger.disabled = true;
  const index = [...$('list').children].findIndex((li) => li.dataset.code === r.code);
  try {
    const saved = await api.act(r.code, body);
    const done = { LINKED: `${r.name}: ${saved.linked?.name ?? ''}(${saved.linked?.id}번)과 이었어요.`, IGNORED: `${r.name}: 맞는 시장 없음으로 처리했어요.`, PENDING: `${r.name}: 다시 확인 대기로 돌렸어요.` }[saved.status];
    toast(done ?? '처리했어요.');
    await loadList(0);
    // 같은 자리의 다음 줄로 (없으면 목록 안내)
    const next = $('list').children[Math.min(index, $('list').children.length - 1)];
    if (next) next.querySelector('button')?.focus();
    else $('panel').focus();
  } catch (e) {
    if (handleAuthError(e)) return;
    toast(e.message);
    trigger.disabled = false;
  }
}

function selectTab(status, { focus = false } = {}) {
  state.status = status;
  document.querySelectorAll('[role="tab"]').forEach((tab) => {
    const on = tab.dataset.status === status;
    tab.setAttribute('aria-selected', String(on));
    tab.tabIndex = on ? 0 : -1;
    if (on && focus) tab.focus();
  });
  $('panel').setAttribute('aria-labelledby', `tab-${status}`);
  loadList(0);
}

function onTabKey(event) {
  const tabs = [...document.querySelectorAll('[role="tab"]')];
  const i = tabs.indexOf(event.currentTarget);
  const next = { ArrowRight: (i + 1) % tabs.length, ArrowLeft: (i - 1 + tabs.length) % tabs.length, Home: 0, End: tabs.length - 1 }[event.key];
  if (next === undefined) return;
  event.preventDefault();
  selectTab(tabs[next].dataset.status, { focus: true });
}

async function init() {
  mountAccount($('account'), { next: '/admin-markets.html', onLogout: () => location.replace('/') });
  const user = await auth.init();
  if (!user) { location.replace(auth.loginUrl('/admin-markets.html')); return; }
  if (user.role !== 'ADMIN') {
    showGate('관리자만 볼 수 있는 화면이에요. 관리자 권한을 받았다면 로그아웃했다가 다시 로그인해 주세요.');
    return;
  }
  $('gate').hidden = true;
  $('workspace').hidden = false;
  document.querySelectorAll('[role="tab"]').forEach((tab) => {
    tab.addEventListener('click', () => selectTab(tab.dataset.status));
    tab.addEventListener('keydown', onTabKey);
  });
  $('moreButton').addEventListener('click', () => loadList(state.page + 1));
  loadList(0);
}

init();
