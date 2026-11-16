// 관리자: 영업 정보 제보 처리 (ADM-01)
// 화면 파일은 누구나 받지만, 내용은 관리자만 쓸 수 있는 /api/admin/** 로만 채운다.
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { todayString } from './festival-format.js';

const $ = (id) => document.getElementById(id);
const STATUS_CLASS = { RECEIVED: 'st-received', APPLIED: 'st-applied', REJECTED: 'st-rejected' };
const INFO_CLASS = { VERIFIED: 'info-verified', UNVERIFIED: 'info-unverified', CLOSED: 'info-closed' };

const state = {
  status: 'RECEIVED',   // 지금 보는 탭
  page: 0,
  store: null,          // 처리 창에 열린 가게 (서버에서 받은 그대로)
  opener: null,         // 처리 창을 닫으면 초점을 돌려줄 버튼
};

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

/** '2026-11-16T14:05:00' → '2026.11.16 14:05' */
function when(value, withTime = true) {
  if (!value) return '';
  const date = value.slice(0, 10).replaceAll('-', '.');
  return withTime ? `${date} ${value.slice(11, 16)}` : date;
}

/** 오늘까지 며칠 기다렸는지 */
function waitingDays(createdAt) {
  const [y, m, d] = createdAt.slice(0, 10).split('-').map(Number);
  const [ty, tm, td] = todayString().split('-').map(Number);
  return Math.max(0, Math.round((Date.UTC(ty, tm - 1, td) - Date.UTC(y, m - 1, d)) / 86400000));
}

// ---------------------------------------------------------------- 서버 요청

class ApiError extends Error {
  constructor(status, body) {
    super(body?.message ?? '잠시 후 다시 시도해 주세요.');
    this.status = status;
    this.code = body?.code ?? `HTTP_${status}`;
    this.details = body?.details ?? [];
  }
}

async function request(url, options = {}) {
  const res = await auth.authFetch(url, {
    ...options,
    headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(options.headers ?? {}) },
  });
  const body = await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(res.status, body);
  return body;
}

const api = {
  counts: () => request('/api/admin/reports/counts'),
  list: (status, page) => request(`/api/admin/reports?status=${status}&page=${page}`),
  store: (id) => request(`/api/admin/stores/${encodeURIComponent(id)}`),
  review: (id, body) => request(`/api/admin/stores/${encodeURIComponent(id)}/review`, { method: 'POST', body: JSON.stringify(body) }),
  edit: (id, body) => request(`/api/admin/stores/${encodeURIComponent(id)}/edit`, { method: 'POST', body: JSON.stringify(body) }),
};

/** 권한이 없거나 로그인이 풀렸을 때 화면 전체를 막는다 */
function handleAuthError(e) {
  if (e.status === 401) {
    location.replace(auth.loginUrl('/admin.html'));
    return true;
  }
  if (e.status === 403) {
    showGate('관리자만 볼 수 있는 화면이에요. 관리자 권한을 받았다면 로그아웃했다가 다시 로그인해 주세요.');
    return true;
  }
  return false;
}

function showGate(message) {
  $('gate').textContent = message;
  $('gate').hidden = false;
  $('workspace').hidden = true;
}

// ---------------------------------------------------------------- 탭과 목록

async function loadCounts() {
  try {
    const c = await api.counts();
    $('count-RECEIVED').textContent = c.received;
    $('count-APPLIED').textContent = c.applied;
    $('count-REJECTED').textContent = c.rejected;
    document.title = c.received ? `(${c.received}) 관리자 - 이음` : '관리자 - 이음';
  } catch (e) {
    handleAuthError(e);
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

// 탭 키보드: 왼쪽·오른쪽 화살표, Home, End (WAI-ARIA 탭 방식)
function onTabKey(event) {
  const tabs = [...document.querySelectorAll('[role="tab"]')];
  const i = tabs.indexOf(event.target);
  const next = { ArrowRight: (i + 1) % tabs.length, ArrowLeft: (i - 1 + tabs.length) % tabs.length, Home: 0, End: tabs.length - 1 }[event.key];
  if (next === undefined) return;
  event.preventDefault();
  selectTab(tabs[next].dataset.status, { focus: true });
}

async function loadList(page) {
  state.page = page;
  const status = state.status;
  if (page === 0) $('list').replaceChildren();
  $('moreButton').hidden = true;
  $('listStatus').textContent = '불러오는 중...';
  try {
    const data = await api.list(status, page);
    if (status !== state.status) return;   // 기다리는 사이 다른 탭을 눌렀으면 버린다
    const items = status === 'RECEIVED' ? data.items.map(pendingCard) : data.items.map(reviewedItem);
    $('list').append(...items);
    $('moreButton').hidden = !data.hasNext;
    const total = status === 'RECEIVED' ? data.totalStores : data.total;
    $('listStatus').textContent = total === 0
      ? (status === 'RECEIVED' ? '처리할 제보가 없어요.' : '아직 없어요.')
      : (status === 'RECEIVED' ? `제보가 기다리는 가게 ${total}곳 · 오래 기다린 순` : `${total}건 · 최근 처리 순`);
    if (page > 0) items[0]?.querySelector('button, a')?.focus();   // '더 보기'로 붙은 첫 항목으로
  } catch (e) {
    if (!handleAuthError(e)) $('listStatus').textContent = '목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
}

function infoBadge(store) {
  return el('span', { class: `info-badge ${INFO_CLASS[store.infoStatus] ?? ''}`, text: store.infoStatusLabel });
}

function reportLine(r, { withStore = false } = {}) {
  return el('div', { class: 'report-line' },
    el('p', { class: 'report-head' },
      el('span', { class: 'type-badge', text: r.typeLabel }),
      withStore ? el('strong', { text: r.storeName }) : null,
      el('span', { class: 'meta', text: `${r.reporterNickname} · ${when(r.createdAt)}` })),
    r.content ? el('p', { class: 'report-content', text: r.content }) : null);
}

/** 처리 대기: 가게 하나 */
function pendingCard({ store, reports }) {
  const days = reports.length ? waitingDays(reports[0].createdAt) : 0;
  const button = el('button', { type: 'button', class: 'button-primary', text: '처리하기', 'aria-label': `${store.name} 제보 처리하기` });
  button.addEventListener('click', () => openStore(store.id, button));
  return el('li', { class: 'admin-card' },
    el('div', { class: 'card-top' },
      el('h2', { class: 'card-title', text: store.name }),
      infoBadge(store),
      el('span', { class: 'chip-count', text: `제보 ${reports.length}건` }),
      el('span', { class: days >= 7 ? 'wait late' : 'wait', text: days === 0 ? '오늘 접수' : `${days}일째 기다림` })),
    el('p', { class: 'card-meta', text: [store.category, store.roadAddress ?? store.jibunAddress].filter(Boolean).join(' · ') }),
    el('div', { class: 'card-reports' }, reports.map((r) => reportLine(r))),
    el('div', { class: 'card-actions' }, button));
}

/** 처리 내역: 제보 한 건 */
function reviewedItem(r) {
  const open = el('button', { type: 'button', class: 'link-button', text: r.storeName, 'aria-label': `${r.storeName} 열기` });
  open.addEventListener('click', () => openStore(r.storeId, open));
  return el('li', { class: 'admin-row' },
    el('p', { class: 'report-head' },
      el('span', { class: `status-badge ${STATUS_CLASS[r.status]}`, text: r.statusLabel }),
      el('span', { class: 'type-badge', text: r.typeLabel }),
      open),
    r.content ? el('p', { class: 'report-content', text: r.content }) : null,
    r.adminNote ? el('p', { class: 'note', text: `처리 메모: ${r.adminNote}` }) : null,
    el('p', { class: 'meta', text: `제보 ${r.reporterNickname} · ${when(r.createdAt)} → 처리 ${r.reviewerNickname ?? ''} · ${when(r.reviewedAt)}` }));
}

// ---------------------------------------------------------------- 처리 창

const form = $('reviewForm');

function pendingReports() {
  // 목록 카드와 같은 순서(먼저 들어온 제보부터)
  return state.store.reports.filter((r) => r.status === 'RECEIVED')
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id - b.id);
}

function checkedReports() {
  const ids = new Set([...form.querySelectorAll('input[name="report"]:checked')].map((i) => Number(i.value)));
  return pendingReports().filter((r) => ids.has(r.id));
}

function decision() {
  return form.querySelector('input[name="decision"]:checked')?.value ?? 'APPLIED';
}

function showDialogAlert(message, details = []) {
  const box = $('dialogAlert');
  // details: 서버가 알려 준 칸별 문제 (예: 전화번호 형식). 없으면 제목만
  const list = details.length ? [el('ul', {}, details.map((d) => el('li', { text: d })))] : [];
  box.replaceChildren(el('strong', { text: message }), ...list);
  box.hidden = false;
  box.focus();
}

function updateDialog() {
  const editOnly = pendingReports().length === 0;
  const rejecting = !editOnly && decision() === 'REJECTED';
  $('pendingSet').hidden = editOnly;
  $('decisionSet').hidden = editOnly;
  $('changeSet').hidden = rejecting;
  $('noteField').hidden = editOnly;
  $('noteRequired').hidden = !rejecting;
  $('note').required = rejecting;
  $('dialogTitle').textContent = editOnly ? '가게 정보 고치기' : '제보 처리';
  $('submitButton').textContent = editOnly ? '저장하기' : rejecting ? '반려하기' : '반영하기';
  // 고른 제보의 종류에 맞는 칸에 '제보됨' 표시 (어느 칸을 확인할지 바로 보이게)
  const types = new Set(checkedReports().map((r) => r.type));
  ['CLOSED', 'HOURS', 'PHONE', 'LOCATION'].forEach((t) => { $(`tag-${t}`).hidden = !types.has(t); });
}

function fillStore(detail) {
  const s = detail.store;
  state.store = detail;
  $('storeName').textContent = s.name;
  $('storeStatus').replaceWith(Object.assign(infoBadge(s), { id: 'storeStatus' }));
  $('storeAddress').textContent = [s.roadAddress, s.jibunAddress && `지번: ${s.jibunAddress}`].filter(Boolean).join('\n') || '없음';
  $('storePhone').textContent = s.phone ?? '없음';
  $('storeHours').textContent = s.hours ?? '없음';
  $('storeLink').hidden = !s.visible;
  $('storeLink').href = `/store.html?id=${s.id}`;

  const pending = pendingReports();
  $('pendingChecks').replaceChildren(...pending.map((r) => el('li', {},
    el('label', { class: 'check' },
      el('input', { type: 'checkbox', name: 'report', value: String(r.id), checked: true }),
      reportLine(r)))));

  form.querySelector('input[name="decision"][value="APPLIED"]').checked = true;
  $('infoStatus').value = s.infoStatus;
  $('hours').value = s.hours ?? '';
  $('phone').value = s.phone ?? '';
  $('roadAddress').value = s.roadAddress ?? '';
  $('note').value = '';
  $('noteCount').textContent = '0/300';

  $('reportHistoryCount').textContent = `(${detail.reports.length})`;
  $('reportHistory').replaceChildren(...detail.reports.map((r) => el('li', {},
    el('p', { class: 'report-head' },
      el('span', { class: `status-badge ${STATUS_CLASS[r.status]}`, text: r.statusLabel }),
      el('span', { class: 'type-badge', text: r.typeLabel }),
      el('span', { class: 'meta', text: `${r.reporterNickname} · ${when(r.createdAt)}` })),
    r.content ? el('p', { class: 'report-content', text: r.content }) : null,
    r.adminNote ? el('p', { class: 'note', text: `처리 메모: ${r.adminNote} (${r.reviewerNickname ?? ''} · ${when(r.reviewedAt)})` }) : null)));
  $('editHistoryCount').textContent = `(${detail.edits.length})`;
  $('editHistory').replaceChildren(...detail.edits.map((e) => el('li', {},
    el('p', { class: 'report-head' },
      el('span', { class: 'type-badge', text: e.fieldLabel }),
      el('span', { class: 'meta', text: `${e.adminNickname ?? ''} · ${when(e.createdAt)}` })),
    el('p', { class: 'edit-change' },
      el('span', { class: 'before', text: e.before ?? '(없음)' }), ' → ', el('span', { class: 'after', text: e.after ?? '(없음)' })))));
  if (!detail.reports.length) $('reportHistory').append(el('li', { class: 'meta', text: '제보가 없어요.' }));
  if (!detail.edits.length) $('editHistory').append(el('li', { class: 'meta', text: '고친 적이 없어요.' }));
  updateDialog();
}

async function openStore(storeId, opener) {
  state.opener = opener ?? null;
  $('dialogAlert').hidden = true;
  $('dialogContent').hidden = true;
  $('dialogLoading').textContent = '불러오는 중...';
  $('submitButton').disabled = true;
  if (!$('reviewDialog').open) $('reviewDialog').showModal();
  try {
    fillStore(await api.store(storeId));
    $('dialogLoading').textContent = '';
    $('dialogContent').hidden = false;
    $('submitButton').disabled = false;
    $('dialogTitle').focus?.();
  } catch (e) {
    if (handleAuthError(e)) { $('reviewDialog').close(); return; }
    $('dialogLoading').textContent = e.code === 'STORE_NOT_FOUND' ? '그 번호의 가게가 없어요.' : '가게 정보를 불러오지 못했어요.';
  }
}

/** 지금 값과 다른 칸만 보낸다 (그대로 둔 칸은 null = 바꾸지 않음) */
function changes() {
  const s = state.store.store;
  const value = (id) => $(id).value.replace(/\s+/g, ' ').trim();
  const diff = {
    infoStatus: $('infoStatus').value !== s.infoStatus ? $('infoStatus').value : null,
    hours: value('hours') !== (s.hours ?? '') ? value('hours') : null,
    phone: value('phone') !== (s.phone ?? '') ? value('phone') : null,
    roadAddress: value('roadAddress') && value('roadAddress') !== (s.roadAddress ?? '') ? value('roadAddress') : null,
  };
  return Object.values(diff).some((v) => v !== null) ? diff : null;
}

function resultMessage(result) {
  const what = result.decision === 'APPLIED' ? `${result.reviewed}건 반영했어요` : result.decision === 'REJECTED' ? `${result.reviewed}건 반려했어요` : '가게 정보를 저장했어요';
  const parts = [what];
  if (result.changed.length) parts.push(`바뀐 정보: ${result.changed.join(', ')}`);
  if (result.changed.includes('영업 상태') && !result.store.visible) parts.push('지도에서 숨겼어요');
  return parts.join(' · ');
}

async function submit(event) {
  event.preventDefault();
  $('dialogAlert').hidden = true;
  const store = state.store.store;
  const editOnly = pendingReports().length === 0;
  const picked = checkedReports();
  const dec = decision();
  const note = $('note').value.trim();
  const diff = editOnly || dec === 'APPLIED' ? changes() : null;

  if (editOnly && !diff) { showDialogAlert('바뀐 칸이 없어요. 고칠 내용을 입력해 주세요.'); return; }
  if (!editOnly && !picked.length) { showDialogAlert('처리할 제보를 하나 이상 골라 주세요.'); return; }
  if (!editOnly && dec === 'REJECTED' && !note) {
    showDialogAlert('반려하는 이유를 처리 메모에 써 주세요. 제보한 분께 보여요.');
    $('note').focus();
    return;
  }

  $('submitButton').disabled = true;
  try {
    const result = editOnly
      ? await api.edit(store.id, diff)
      : await api.review(store.id, { reportIds: picked.map((r) => r.id), decision: dec, note: note || null, changes: diff });
    $('reviewDialog').close('done');
    toast(resultMessage(result) + (result.warning ? ` (${result.warning})` : ''), 7000);
    loadCounts();
    loadList(0);
  } catch (e) {
    if (handleAuthError(e)) { $('reviewDialog').close(); return; }
    showDialogAlert(e.message, e.details);
    if (e.code === 'REPORT_ALREADY_REVIEWED') {
      // 다른 관리자가 먼저 처리했다: 최신 상태로 다시 그린다
      await openStore(store.id, state.opener);
      showDialogAlert(e.message);
      loadCounts();
      loadList(0);
    }
  } finally {
    $('submitButton').disabled = false;
  }
}

// ---------------------------------------------------------------- 시작

async function init() {
  mountAccount($('account'), { next: '/admin.html', onLogout: () => location.replace('/') });
  const user = await auth.init();
  if (!user) {
    location.replace(auth.loginUrl('/admin.html'));
    return;
  }
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
  $('openStoreForm').addEventListener('submit', (event) => {
    event.preventDefault();
    const id = $('storeIdInput').value.trim();
    if (!/^\d{1,10}$/.test(id)) {
      $('storeIdInput').setAttribute('aria-invalid', 'true');
      toast('가게 번호는 숫자로 입력해 주세요.');
      return;
    }
    $('storeIdInput').removeAttribute('aria-invalid');
    openStore(id, event.submitter ?? null);
  });

  form.addEventListener('change', (event) => {
    if (event.target.name === 'decision' || event.target.name === 'report') updateDialog();
  });
  form.addEventListener('submit', submit);
  $('note').addEventListener('input', () => { $('noteCount').textContent = `${$('note').value.length}/300`; });
  $('closeDialog').addEventListener('click', () => $('reviewDialog').close());
  $('cancelButton').addEventListener('click', () => $('reviewDialog').close());
  $('reviewDialog').addEventListener('close', () => {
    // 처리해서 목록이 다시 그려졌으면 opener가 사라졌을 수 있다 → 목록 첫 항목이나 탭으로
    const target = state.opener?.isConnected ? state.opener : $('list').querySelector('button') ?? $(`tab-${state.status}`);
    target?.focus();
  });

  loadCounts();
  selectTab('RECEIVED');
}

init();
