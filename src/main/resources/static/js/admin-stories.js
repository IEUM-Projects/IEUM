// 관리자: 시장 이야기 (MKT-02) — 초안 확인, 고치기, 공개·숨기기, 새로 쓰기
import * as auth from './auth.js';
import { mountAccount } from './header.js';

const $ = (id) => document.getElementById(id);
const STATUS = { DRAFT: '초안', PUBLISHED: '공개', HIDDEN: '숨김' };
const MAX_SOURCES = 8;
const state = { status: 'DRAFT', page: 0, editing: null, opener: null };

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
    super(body?.message ?? '잠시 후 다시 시도해 주세요.');
    this.status = status;
    this.details = body?.details ?? [];
  }
}

async function request(url, options = {}) {
  const res = await auth.authFetch(url, { ...options, headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}) } });
  const body = await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(res.status, body);
  return body;
}

const api = {
  list: (status, page) => request(`/api/admin/stories?status=${status}&page=${page}`),
  markets: (q) => request(`/api/admin/stories/markets?q=${encodeURIComponent(q)}`),
  get: (id) => request(`/api/admin/stories/${id}`),
  save: (id, body) => request(`/api/admin/stories/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  publish: (id) => request(`/api/admin/stories/${id}/publish`, { method: 'POST' }),
  hide: (id) => request(`/api/admin/stories/${id}/hide`, { method: 'POST' }),
};

function showGate(message) {
  $('gate').textContent = message;
  $('gate').hidden = false;
  $('workspace').hidden = true;
}

function handleAuthError(e) {
  if (e.status === 401) { location.replace(auth.loginUrl('/admin-stories.html')); return true; }
  if (e.status === 403) { showGate('관리자만 볼 수 있는 화면이에요. 관리자 권한을 받았다면 로그아웃했다가 다시 로그인해 주세요.'); return true; }
  return false;
}

const day = (iso) => (iso ? iso.slice(0, 10).replaceAll('-', '.') : '');

// ---------------------------------------------------------------- 목록

function row(s) {
  const edit = el('button', { type: 'button', class: 'button-secondary', text: '확인·고치기', 'aria-label': `${s.marketName} 이야기 확인·고치기` });
  edit.addEventListener('click', () => openEditor(s.marketId, edit));
  const quick = s.status === 'PUBLISHED'
    ? el('button', { type: 'button', class: 'button-secondary', text: '숨기기', 'aria-label': `${s.marketName} 이야기 숨기기` })
    : null;
  quick?.addEventListener('click', () => changeStatus(s.marketId, 'hide', quick));
  return el('li', { class: 'admin-row notice-admin-row story-row' },
    el('div', { class: 'report-head' },
      el('span', { class: `status-badge st-${s.status.toLowerCase()}`, text: STATUS[s.status] }),
      s.origin === 'RESEARCH' && !s.edited ? el('span', { class: 'status-badge st-research', text: '자료 조사 초안' }) : null,
      el('h2', { text: s.marketName })),
    el('p', { class: 'story-row-meta', text: `${s.address ?? ''} · 출처 ${s.sourceCount}개 · ${day(s.updatedAt)} 고침${s.publishedAt ? ` · ${day(s.publishedAt)} 공개` : ''}` }),
    el('p', { class: 'story-row-preview', text: `${s.preview}${s.preview.length >= 80 ? '…' : ''}` }),
    el('div', { class: 'row-actions' }, edit, quick,
      el('a', { class: 'button-secondary', href: `/market.html?id=${s.marketId}`, target: '_blank', rel: 'noopener', text: '시장 화면', 'aria-label': `${s.marketName} 시장 화면 (새 창)` })));
}

async function loadList(append = false) {
  if (!append) { state.page = 0; $('list').replaceChildren(); }
  $('listStatus').textContent = '불러오는 중...';
  $('moreButton').hidden = true;
  try {
    const data = await api.list(state.status, state.page);
    Object.entries(data.counts).forEach(([k, n]) => { $(`count-${k}`).textContent = String(n); });
    const rows = data.items.map(row);
    $('list').append(...rows);
    $('listStatus').textContent = data.total ? `${STATUS[state.status]} ${data.total}개` : `${STATUS[state.status]} 이야기가 없어요.`;
    $('moreButton').hidden = !data.hasNext;
    if (append) rows[0]?.querySelector('button')?.focus();
    $('workspace').hidden = false;
    $('gate').hidden = true;
  } catch (e) {
    if (!handleAuthError(e)) $('listStatus').textContent = e.message;
  }
}

function selectTab(status, focus) {
  state.status = status;
  document.querySelectorAll('[role="tab"]').forEach((t) => {
    const on = t.dataset.status === status;
    t.setAttribute('aria-selected', String(on));
    t.tabIndex = on ? 0 : -1;
    if (on && focus) t.focus();
  });
  $('panel').setAttribute('aria-labelledby', `tab-${status}`);
  loadList();
}

async function changeStatus(id, action, button) {
  button.disabled = true;
  try {
    await api[action](id);
    toast(action === 'hide' ? '숨겼어요. 시장 화면에서 더는 보이지 않아요.' : '공개했어요. 시장 화면에 보여요.');
    await loadList();
    $('panel').focus();
  } catch (e) {
    if (!handleAuthError(e)) toast(e.message);
    button.disabled = false;
  }
}

// ---------------------------------------------------------------- 시장 찾기

async function findMarkets(event) {
  event.preventDefault();
  const q = $('findQ').value.trim();
  $('findList').replaceChildren();
  if (q.length < 2) { $('findStatus').textContent = '시장 이름을 2자 이상 넣어 주세요.'; return; }
  $('findStatus').textContent = '찾는 중...';
  try {
    const hits = await api.markets(q);
    $('findStatus').textContent = hits.length ? `시장 ${hits.length}곳` : '찾은 시장이 없어요.';
    $('findList').replaceChildren(...hits.map((h) => {
      const b = el('button', { type: 'button', class: 'button-secondary', text: h.storyStatus ? `${STATUS[h.storyStatus]} 이야기 고치기` : '이야기 쓰기', 'aria-label': `${h.name} ${h.storyStatus ? '이야기 고치기' : '이야기 쓰기'}` });
      b.addEventListener('click', () => openEditor(h.id, b));
      return el('li', { class: 'find-item' }, el('span', {}, el('strong', { text: h.name }), el('span', { class: 'find-addr', text: h.address ?? '' })), b);
    }));
  } catch (e) {
    if (!handleAuthError(e)) $('findStatus').textContent = e.details[0] ?? e.message;
  }
}

// ---------------------------------------------------------------- 편집

function sourceRow(src = { title: '', url: '' }) {
  const li = el('li', { class: 'source-row' });
  const n = () => [...$('sourceRows').children].indexOf(li) + 1;
  const title = el('input', { type: 'text', class: 'src-title', maxlength: '100', autocomplete: 'off', placeholder: '자료 이름 (예: 국가기록원 「…」)' });
  const url = el('input', { type: 'url', class: 'src-url', maxlength: '500', autocomplete: 'off', placeholder: 'https://', inputmode: 'url' });
  title.value = src.title;
  url.value = src.url;
  const remove = el('button', { type: 'button', class: 'button-secondary src-remove', text: '빼기' });
  remove.addEventListener('click', () => {
    const next = li.nextElementSibling ?? li.previousElementSibling;
    li.remove();
    renumber();
    (next?.querySelector('input') ?? $('addSource')).focus();
  });
  li.append(title, url, remove);
  li.renumber = () => {
    const i = n();
    title.setAttribute('aria-label', `출처 ${i} 자료 이름`);
    url.setAttribute('aria-label', `출처 ${i} 링크`);
    remove.setAttribute('aria-label', `출처 ${i} 빼기`);
  };
  return li;
}

function renumber() {
  [...$('sourceRows').children].forEach((li) => li.renumber());
  $('addSource').disabled = $('sourceRows').children.length >= MAX_SOURCES;
}

function addSource(src) {
  $('sourceRows').append(sourceRow(src));
  renumber();
}

function count() {
  $('bodyCount').textContent = `${$('body').value.length}/3000`;
}

function showAlert(message, details = []) {
  const box = $('dialogAlert');
  box.replaceChildren(message, ...(details.length ? [el('ul', {}, details.map((d) => el('li', { text: d })))] : []));
  box.hidden = false;
  box.focus();
}

function fill(data) {
  state.editing = data.marketId;
  const s = data.story;
  $('dialogTitle').textContent = `${data.marketName} 이야기`;
  $('dialogState').textContent = s
    ? `지금 상태: ${STATUS[s.status]}${s.origin === 'RESEARCH' && !s.edited ? ' · 이음이 공개 자료로 쓴 초안이에요. 출처를 열어 내용이 맞는지 확인해 주세요.' : ''}`
    : '아직 이야기가 없어요. 저장하면 초안으로 들어가요.';
  $('body').value = s?.body ?? '';
  $('sourceRows').replaceChildren();
  (s?.sources?.length ? s.sources : [{ title: '', url: '' }]).forEach(addSource);
  $('tourRef').hidden = !data.tour;
  $('tourText').textContent = data.tour ? `${data.tour.overview}\n\n출처: ${data.tour.source} ('${data.tour.title}')` : '';
  $('publishButton').hidden = s?.status === 'PUBLISHED';
  $('saveButton').textContent = s?.status === 'PUBLISHED' ? '저장 (공개 중인 글이 바로 바뀌어요)' : '저장';
  count();
}

async function openEditor(id, opener) {
  state.opener = opener ?? null;
  $('dialogAlert').hidden = true;
  try {
    fill(await api.get(id));
    $('storyDialog').showModal();
    $('body').focus();
  } catch (e) {
    if (!handleAuthError(e)) toast(e.message);
  }
}

function collect() {
  return {
    body: $('body').value,
    sources: [...$('sourceRows').children].map((li) => ({ title: li.querySelector('.src-title').value, url: li.querySelector('.src-url').value })),
  };
}

async function submit(publish) {
  $('dialogAlert').hidden = true;
  const buttons = [$('saveButton'), $('publishButton')];
  buttons.forEach((b) => { b.disabled = true; });
  try {
    await api.save(state.editing, collect());
    if (publish) await api.publish(state.editing);
    $('storyDialog').close();
    toast(publish ? '저장하고 공개했어요. 시장 화면에 보여요.' : '저장했어요.');
    await loadList();
  } catch (e) {
    if (handleAuthError(e)) { $('storyDialog').close(); return; }
    showAlert(e.message, e.details);
  } finally {
    buttons.forEach((b) => { b.disabled = false; });
  }
}

function closeDialog() {
  $('storyDialog').close();
}

async function init() {
  mountAccount($('account'));
  await auth.init();
  $('findForm').addEventListener('submit', findMarkets);
  const tabs = [...document.querySelectorAll('[role="tab"]')];
  tabs.forEach((t, i) => {
    t.addEventListener('click', () => selectTab(t.dataset.status));
    t.addEventListener('keydown', (e) => {
      const n = { ArrowRight: i + 1, ArrowLeft: i - 1, Home: 0, End: tabs.length - 1 }[e.key];
      if (n === undefined) return;
      e.preventDefault();
      selectTab(tabs[(n + tabs.length) % tabs.length].dataset.status, true);
    });
  });
  $('moreButton').addEventListener('click', () => { state.page += 1; loadList(true); });
  $('body').addEventListener('input', count);
  $('addSource').addEventListener('click', () => {
    addSource();
    $('sourceRows').lastElementChild.querySelector('input').focus();
  });
  $('storyForm').addEventListener('submit', (e) => { e.preventDefault(); submit(false); });
  $('publishButton').addEventListener('click', () => submit(true));
  $('cancelButton').addEventListener('click', closeDialog);
  $('closeDialog').addEventListener('click', closeDialog);
  $('storyDialog').addEventListener('close', () => { state.opener?.focus?.(); });
  selectTab('DRAFT');
}

init();
