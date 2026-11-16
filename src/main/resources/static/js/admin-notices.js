// 관리자: 공지사항 쓰기·고치기·숨기기 (NOT-01)
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { noticeDate } from './notice-format.js';

const $ = (id) => document.getElementById(id);
const state = { page: 0, editing: null, opener: null };

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
    this.code = body?.code ?? `HTTP_${status}`;
    this.details = body?.details ?? [];
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
  list: (page) => request(`/api/admin/notices?page=${page}`),
  get: (id) => request(`/api/admin/notices/${id}`),
  write: (body) => request('/api/admin/notices', { method: 'POST', body: JSON.stringify(body) }),
  edit: (id, body) => request(`/api/admin/notices/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
};

function showGate(message) {
  $('gate').textContent = message;
  $('gate').hidden = false;
  $('workspace').hidden = true;
}

function handleAuthError(e) {
  if (e.status === 401) { location.replace(auth.loginUrl('/admin-notices.html')); return true; }
  if (e.status === 403) {
    showGate('관리자만 볼 수 있는 화면이에요. 관리자 권한을 받았다면 로그아웃했다가 다시 로그인해 주세요.');
    return true;
  }
  return false;
}

// ---------------------------------------------------------------- 목록

function row(n) {
  const edit = el('button', { type: 'button', class: 'button-secondary', text: '고치기', 'aria-label': `${n.title} 고치기` });
  edit.addEventListener('click', () => openEditor(n.id, edit));
  const toggle = el('button', { type: 'button', class: 'button-secondary', text: n.visible ? '숨기기' : '다시 공개', 'aria-label': `${n.title} ${n.visible ? '숨기기' : '다시 공개'}` });
  toggle.addEventListener('click', () => quickToggle(n.id, toggle));
  return el('li', { class: `admin-row notice-admin-row${n.visible ? '' : ' is-hidden'}` },
    el('div', { class: 'report-head' },
      n.pinned ? el('span', { class: 'status-badge st-pinned', text: '고정' }) : null,
      n.visible ? null : el('span', { class: 'status-badge st-hidden', text: '숨김' }),
      el('h2', { text: n.title })),
    el('p', { class: 'meta', text: `${n.authorNickname} · ${noticeDate(n.createdAt)}${n.updatedAt && n.updatedAt !== n.createdAt ? ` · ${noticeDate(n.updatedAt)} 수정` : ''}` }),
    el('div', { class: 'row-actions' },
      n.visible ? el('a', { class: 'button-secondary', href: `/notice.html?id=${n.id}`, target: '_blank', rel: 'noopener', text: '보기', 'aria-label': `${n.title} 보기 (새 창)` }) : null,
      edit, toggle));
}

async function loadList(page = 0) {
  state.page = page;
  if (page === 0) $('list').replaceChildren();
  $('listStatus').textContent = '불러오는 중...';
  try {
    const data = await api.list(page);
    $('list').append(...data.items.map(row));
    $('moreButton').hidden = !data.hasNext;
    $('listStatus').textContent = data.total ? `공지 ${data.total}개 (숨긴 공지 포함) · 고정 먼저, 최신 순` : '아직 공지가 없어요. 첫 공지를 써 보세요.';
  } catch (e) {
    if (!handleAuthError(e)) $('listStatus').textContent = '목록을 불러오지 못했어요.';
  }
}

/** 숨기기 / 다시 공개: 내용은 그대로 두고 공개 여부만 바꾼다 */
async function quickToggle(id, button) {
  button.disabled = true;
  try {
    const n = await api.get(id);
    const saved = await api.edit(id, { title: n.title, content: n.content, pinned: n.pinned, visible: !n.visible });
    toast(saved.visible ? '다시 공개했어요.' : '숨겼어요. 이제 관리자만 볼 수 있어요.');
    await loadList(0);
    $('list').querySelector('button')?.focus();
  } catch (e) {
    if (!handleAuthError(e)) toast(e.code === 'TOO_MANY_PINNED' ? '고정 공지가 이미 3개예요. 다른 공지의 고정을 먼저 풀어 주세요.' : e.message);
    button.disabled = false;
  }
}

// ---------------------------------------------------------------- 쓰기·고치기 창

function showAlert(message, details = []) {
  const box = $('dialogAlert');
  const list = details.length ? [el('ul', {}, details.map((d) => el('li', { text: d })))] : [];
  box.replaceChildren(el('strong', { text: message }), ...list);
  box.hidden = false;
  box.focus();
}

function counts() {
  $('titleCount').textContent = `${$('title').value.length}/100`;
  $('contentCount').textContent = `${$('content').value.length}/5000`;
}

async function openEditor(id, opener) {
  state.editing = id ?? null;
  state.opener = opener ?? null;
  $('dialogAlert').hidden = true;
  $('dialogTitle').textContent = id ? '공지 고치기' : '새 공지 쓰기';
  $('submitButton').textContent = id ? '저장하기' : '올리기';
  $('title').value = '';
  $('content').value = '';
  $('visible').checked = true;
  $('pinned').checked = false;
  if (id) {
    try {
      const n = await api.get(id);
      $('title').value = n.title;
      $('content').value = n.content;
      $('visible').checked = n.visible;
      $('pinned').checked = n.pinned;
    } catch (e) {
      if (!handleAuthError(e)) toast('공지를 불러오지 못했어요.');
      return;
    }
  }
  counts();
  $('noticeDialog').showModal();
  $('title').focus();
}

async function submit(event) {
  event.preventDefault();
  $('dialogAlert').hidden = true;
  const body = {
    title: $('title').value.trim(),
    content: $('content').value,
    pinned: $('pinned').checked,
    visible: $('visible').checked,
  };
  const problems = [];
  if (body.title.length < 2) problems.push('제목은 2~100자로 써 주세요.');
  if (!body.content.trim()) problems.push('내용을 입력해 주세요.');
  if (problems.length) {
    showAlert('입력값을 확인해 주세요.', problems);
    return;
  }
  $('submitButton').disabled = true;
  try {
    const saved = state.editing ? await api.edit(state.editing, body) : await api.write(body);
    $('noticeDialog').close('done');
    toast(state.editing ? '공지를 고쳤어요.' : saved.visible ? '공지를 올렸어요.' : '공지를 숨긴 채로 저장했어요.');
    await loadList(0);
  } catch (e) {
    if (handleAuthError(e)) { $('noticeDialog').close(); return; }
    showAlert(e.message, e.details);
  } finally {
    $('submitButton').disabled = false;
  }
}

// ---------------------------------------------------------------- 시작

async function init() {
  mountAccount($('account'), { next: '/admin-notices.html', onLogout: () => location.replace('/') });
  const user = await auth.init();
  if (!user) { location.replace(auth.loginUrl('/admin-notices.html')); return; }
  if (user.role !== 'ADMIN') {
    showGate('관리자만 볼 수 있는 화면이에요. 관리자 권한을 받았다면 로그아웃했다가 다시 로그인해 주세요.');
    return;
  }
  $('gate').hidden = true;
  $('workspace').hidden = false;
  $('newButton').addEventListener('click', (e) => openEditor(null, e.currentTarget));
  $('moreButton').addEventListener('click', () => loadList(state.page + 1));
  $('noticeForm').addEventListener('submit', submit);
  $('title').addEventListener('input', counts);
  $('content').addEventListener('input', counts);
  $('closeDialog').addEventListener('click', () => $('noticeDialog').close());
  $('cancelButton').addEventListener('click', () => $('noticeDialog').close());
  $('noticeDialog').addEventListener('close', () => {
    const target = state.opener?.isConnected ? state.opener : $('newButton');
    target.focus();
  });
  loadList(0);
}

init();
