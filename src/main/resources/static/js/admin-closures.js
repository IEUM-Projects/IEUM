// 관리자: 폐업 의심 가게 (DATA-02)
import * as auth from './auth.js';
import { mountAccount } from './header.js';

const $ = (id) => document.getElementById(id);
const REVIEWS = ['PENDING', 'CLOSED', 'DISMISSED'];
const REVIEW_LABELS = { PENDING: '확인 대기', CLOSED: '폐업 처리함', DISMISSED: '영업 중 맞음' };
const RESULT_LABELS = { OPEN: '영업 중', CLOSED: '폐업', SUSPENDED: '휴업', NOT_FOUND: '찾지 못함', NOT_TARGET: '확인 대상 아님', ERROR: '오류' };
const state = { review: 'PENDING', page: 0, polling: null };

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

function toast(message, ms = 6000) {
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
  summary: () => request('/api/admin/closures/summary'),
  list: (review, page) => request(`/api/admin/closures?review=${review}&page=${page}`),
  act: (id, action) => request(`/api/admin/closures/${id}`, { method: 'POST', body: JSON.stringify({ action }) }),
  upload: (form) => auth.authFetch('/api/admin/closures/import', { method: 'POST', body: form }),
};

function showGate(message) {
  $('gate').textContent = message;
  $('gate').hidden = false;
  $('workspace').hidden = true;
}

function handleAuthError(e) {
  if (e.status === 401) { location.replace(auth.loginUrl('/admin-closures.html')); return true; }
  if (e.status === 403) {
    showGate('관리자만 볼 수 있는 화면이에요. 관리자 권한을 받았다면 로그아웃했다가 다시 로그인해 주세요.');
    return true;
  }
  return false;
}

const dateTime = (s) => (s ? `${s.slice(0, 10).replaceAll('-', '.')} ${s.slice(11, 16)}` : '');

// ---------------------------------------------------------------- 현황

async function loadSummary() {
  try {
    const s = await api.summary();
    $('summaryMain').textContent = `${s.targetStores.toLocaleString()}곳 중 ${s.checkedStores.toLocaleString()}곳 확인함`
      + (s.lastCheckedAt ? ` · 마지막 확인 ${dateTime(s.lastCheckedAt)}` : ' · 아직 확인 전');
    $('summaryCounts').replaceChildren(...['OPEN', 'CLOSED', 'SUSPENDED', 'NOT_FOUND', 'NOT_TARGET']
      .map((r) => el('li', {}, el('span', { class: 'sc-label', text: RESULT_LABELS[r] }), el('strong', { text: `${(s.results[r] ?? 0).toLocaleString()}곳` }))));
    $('summaryNote').textContent = `확인하는 인허가: ${s.kinds.join(', ')} · 도소매·공방처럼 인허가가 없는 업종은 확인할 수 없어요.`;
    REVIEWS.forEach((r) => { $(`count-${r}`).textContent = (s.reviews[r] ?? 0).toLocaleString(); });
    showJob(s.job);
    return s;
  } catch (e) {
    if (!handleAuthError(e)) $('summaryMain').textContent = '현황을 불러오지 못했어요.';
    return null;
  }
}

/** 파일 확인 진행 상황 */
function showJob(job) {
  const running = job?.state === 'RUNNING';
  $('importButton').disabled = running;
  $('fileInput').disabled = running;
  if (!job) return;
  if (running) {
    $('importStatus').textContent = job.currentFile
      ? `파일 ${job.fileIndex}/${job.fileCount} 읽는 중 (${job.currentFile}) · ${job.rowsRead.toLocaleString()}줄 읽음`
      : `파일 ${job.fileCount}개 받음 · 가게와 맞추는 중...`;
  } else if (job.state === 'DONE') {
    const parts = ['OPEN', 'CLOSED', 'SUSPENDED', 'NOT_FOUND', 'NOT_TARGET'].filter((k) => job.results[k]).map((k) => `${RESULT_LABELS[k]} ${job.results[k]}`);
    $('importStatus').textContent = `${dateTime(job.finishedAt)} 확인을 마쳤어요. ${job.rowsRead.toLocaleString()}줄에서 가게 ${job.changed.toLocaleString()}곳 확인`
      + (parts.length ? ` (${parts.join(', ')})` : '') + (job.skipped ? ` · 파일이 없는 지역 ${job.skipped}곳은 그대로` : '') + '.';
  } else {
    $('importStatus').textContent = job.message ?? '확인하지 못했어요.';
  }
  $('importFiles').replaceChildren(...job.files.map((f) => el('li', { class: f.error ? 'has-error' : '' },
    el('strong', { text: f.name }),
    el('span', { text: f.error ? ` · ${f.error}` : ` · ${f.kind} · ${f.rows.toLocaleString()}줄 · ${f.regions.slice(0, 6).join(', ')}${f.regions.length > 6 ? ' 등' : ''}` }))));
  if (running && !state.polling) {
    state.polling = setInterval(async () => {
      const s = await loadSummary();
      if (s?.job?.state !== 'RUNNING') {
        clearInterval(state.polling);
        state.polling = null;
        loadList(0);
      }
    }, 2000);
  }
}

async function upload(event) {
  event.preventDefault();
  const files = [...$('fileInput').files];
  if (!files.length) {
    $('importStatus').textContent = '인허가 CSV 파일을 골라 주세요.';
    $('fileInput').focus();
    return;
  }
  const form = new FormData();
  files.forEach((f) => form.append('files', f));
  $('importButton').disabled = true;
  const size = files.reduce((n, f) => n + f.size, 0);
  $('importStatus').textContent = `파일 ${files.length}개 (${(size / 1048576).toFixed(0)}MB)를 올리는 중...`;
  try {
    const res = await api.upload(form);
    const body = await res.json().catch(() => null);
    if (!res.ok) throw new ApiError(res.status, body);
    $('fileInput').value = '';
    showJob(body);
  } catch (e) {
    if (handleAuthError(e)) return;
    $('importStatus').textContent = e.message;
    $('importButton').disabled = false;
  }
}

// ---------------------------------------------------------------- 한 줄

function button(text, label, onClick, cls = 'button-secondary') {
  const b = el('button', { type: 'button', class: cls, text, 'aria-label': label });
  b.addEventListener('click', () => onClick(b));
  return b;
}

function row(c) {
  const actions = el('div', { class: 'row-actions' });
  const confirm = el('div', { class: 'confirm-box', id: `confirm-${c.storeId}`, hidden: true },
    el('p', { text: '폐업 처리하면 지도와 목록에서 숨겨지고, 수정 기록이 남아요.' }),
    button('폐업 처리하기', `${c.storeName} 폐업 처리하기`, (b) => act(c, 'CLOSE', b), 'button-danger'),
    button('취소', `${c.storeName} 폐업 처리 취소`, () => { confirm.hidden = true; closeButton.setAttribute('aria-expanded', 'false'); closeButton.focus(); }));
  let closeButton = null;
  if (c.review === 'PENDING') {
    closeButton = button('폐업 처리', `${c.storeName} 폐업 처리`, (b) => {
      confirm.hidden = false;
      b.setAttribute('aria-expanded', 'true');
      confirm.querySelector('button').focus();
    }, 'button-primary');
    closeButton.setAttribute('aria-expanded', 'false');
    closeButton.setAttribute('aria-controls', `confirm-${c.storeId}`);
    actions.append(closeButton, button('영업 중 맞음', `${c.storeName} 영업 중 맞음`, (b) => act(c, 'DISMISS', b)));
  } else {
    actions.append(button('다시 확인 대기로', `${c.storeName} 다시 확인 대기로`, (b) => act(c, 'RESET', b)));
  }
  const statusText = `${c.licenseStatus ?? RESULT_LABELS[c.result]}${c.closedOn ? ` (폐업일 ${c.closedOn.replaceAll('-', '.')})` : ''}`;
  return el('li', { class: 'admin-row link-row', 'data-id': String(c.storeId) },
    el('div', { class: 'report-head' },
      el('span', { class: `status-badge st-closure-${c.result.toLowerCase()}`, text: RESULT_LABELS[c.result] }),
      el('h2', {}, el('a', { href: `/store.html?id=${c.storeId}`, target: '_blank', rel: 'noopener', text: c.storeName ?? `${c.storeId}번 가게`, 'aria-label': `${c.storeName} 가게 화면 (새 창)` })),
      c.infoStatus === 'CLOSED' ? el('span', { class: 'status-badge st-link-ignored', text: '지도에서 숨김' }) : null),
    el('p', { class: 'meta', text: `${c.storeId}번 · ${c.category ?? '분류 없음'} · 확인 ${dateTime(c.checkedAt)}${c.reviewedAt ? ` · 처리 ${dateTime(c.reviewedAt)}` : ''}` }),
    el('dl', { class: 'compare' },
      el('div', {}, el('dt', { text: '이음' }), el('dd', { text: `${c.storeName ?? ''} · ${c.roadAddress ?? '주소 없음'}` })),
      el('div', {}, el('dt', { text: `인허가 (${c.licenseKind ?? '-'})` }), el('dd', { text: `${c.licenseName ?? '-'} · ${c.licenseAddress ?? '주소 없음'}` })),
      el('div', {}, el('dt', { text: '영업상태' }), el('dd', {}, el('strong', { text: statusText }))),
      c.matchNote ? el('div', {}, el('dt', { text: '맞춘 방법' }), el('dd', { text: c.matchNote })) : null),
    actions, confirm);
}

// ---------------------------------------------------------------- 목록

async function loadList(page = 0) {
  state.page = page;
  if (page === 0) $('list').replaceChildren();
  $('moreButton').hidden = true;
  $('listStatus').textContent = '불러오는 중...';
  try {
    const data = await api.list(state.review, page);
    REVIEWS.forEach((r) => { $(`count-${r}`).textContent = (data.counts[r] ?? 0).toLocaleString(); });
    const nodes = data.items.map(row);
    $('list').append(...nodes);
    $('moreButton').hidden = !data.hasNext;
    $('listStatus').textContent = data.total
      ? `${REVIEW_LABELS[state.review]} ${data.total.toLocaleString()}곳 · 최근 확인 순`
      : state.review === 'PENDING' ? '확인할 가게가 없어요.' : `${REVIEW_LABELS[state.review]} 가게가 없어요.`;
    if (page > 0 && nodes.length) nodes[0].querySelector('button')?.focus();
  } catch (e) {
    if (!handleAuthError(e)) $('listStatus').textContent = '목록을 불러오지 못했어요.';
  }
}

async function act(c, action, trigger) {
  trigger.disabled = true;
  const index = [...$('list').children].findIndex((li) => li.dataset.id === String(c.storeId));
  try {
    await api.act(c.storeId, action);
    toast({
      CLOSE: `${c.storeName}: 폐업 처리했어요. 다시 열려면 제보 처리 화면에서 가게 번호 ${c.storeId}를 열어 '영업 확인'으로 바꿔 주세요.`,
      DISMISS: `${c.storeName}: 영업 중으로 두었어요. 같은 인허가 내용이면 다시 올리지 않아요.`,
      RESET: `${c.storeName}: 다시 확인 대기로 돌렸어요.`,
    }[action]);
    await loadList(0);
    loadSummary();
    const next = $('list').children[Math.min(index, $('list').children.length - 1)];
    if (next) next.querySelector('button')?.focus();
    else $('panel').focus();
  } catch (e) {
    if (handleAuthError(e)) return;
    toast(e.message);
    if (e.code === 'CLOSURE_ALREADY_REVIEWED') loadList(0);
    trigger.disabled = false;
  }
}

function selectTab(review, { focus = false } = {}) {
  state.review = review;
  document.querySelectorAll('[role="tab"]').forEach((tab) => {
    const on = tab.dataset.review === review;
    tab.setAttribute('aria-selected', String(on));
    tab.tabIndex = on ? 0 : -1;
    if (on && focus) tab.focus();
  });
  $('panel').setAttribute('aria-labelledby', `tab-${review}`);
  loadList(0);
}

function onTabKey(event) {
  const tabs = [...document.querySelectorAll('[role="tab"]')];
  const i = tabs.indexOf(event.currentTarget);
  const next = { ArrowRight: (i + 1) % tabs.length, ArrowLeft: (i - 1 + tabs.length) % tabs.length, Home: 0, End: tabs.length - 1 }[event.key];
  if (next === undefined) return;
  event.preventDefault();
  selectTab(tabs[next].dataset.review, { focus: true });
}

async function init() {
  mountAccount($('account'), { next: '/admin-closures.html', onLogout: () => location.replace('/') });
  const user = await auth.init();
  if (!user) { location.replace(auth.loginUrl('/admin-closures.html')); return; }
  if (user.role !== 'ADMIN') {
    showGate('관리자만 볼 수 있는 화면이에요. 관리자 권한을 받았다면 로그아웃했다가 다시 로그인해 주세요.');
    return;
  }
  $('gate').hidden = true;
  $('workspace').hidden = false;
  document.querySelectorAll('[role="tab"]').forEach((tab) => {
    tab.addEventListener('click', () => selectTab(tab.dataset.review));
    tab.addEventListener('keydown', onTabKey);
  });
  $('moreButton').addEventListener('click', () => loadList(state.page + 1));
  $('importForm').addEventListener('submit', upload);
  loadSummary();
  loadList(0);
}

init();
