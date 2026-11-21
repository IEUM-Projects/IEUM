// 관리자: 회원 관리 (ADM-02) - 회원 찾기, 작성 정지, 강제 탈퇴, 재가입 허용
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { el, request } from './community-common.js';

const $ = (id) => document.getElementById(id);
const PAGE = '/admin-users.html';
const COUNT_KEYS = ['ALL', 'ACTIVE', 'SUSPENDED', 'BANNED', 'WITHDRAWN', 'ADMIN'];
const STATUS_LABELS = { ACTIVE: '활동 중', SUSPENDED: '작성 정지', WITHDRAWN: '탈퇴' };
const PROVIDER_LABELS = { LOCAL: '이메일', KAKAO: '카카오' };
const POST_STATUS = { VISIBLE: '보이는 중', HIDDEN: '가림', TEMP_HIDDEN: '임시 숨김', DELETED: '지움' };

const state = { tab: '', q: '', page: 0, items: [], current: null, opener: null };

const api = {
  list: (q, status, page) => {
    const p = new URLSearchParams({ page: String(page) });
    if (q) p.set('q', q);
    if (status) p.set('status', status);
    return request(`/api/admin/users?${p}`);
  },
  detail: (id) => request(`/api/admin/users/${id}`),
  suspend: (id, on) => request(`/api/admin/users/${id}/suspend`, { method: 'POST', body: JSON.stringify({ suspend: on }) }),
  withdraw: (id, reason) => request(`/api/admin/users/${id}/withdraw`, { method: 'POST', body: JSON.stringify({ reason }) }),
  rejoin: (id) => request(`/api/admin/users/${id}/ban`, { method: 'DELETE' }),
};

function toast(message, ms = 5000) {
  $('toast').textContent = message;
  $('toast').hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { $('toast').hidden = true; }, ms);
}

function showGate(message) {
  $('gate').textContent = message;
  $('gate').hidden = false;
  $('workspace').hidden = true;
  if ($('memberDialog').open) $('memberDialog').close();
}

function handleAuthError(e) {
  if (e.status === 401) { location.replace(auth.loginUrl(PAGE)); return true; }
  if (e.status === 403 && e.code !== 'WRITE_SUSPENDED') {
    showGate('관리자만 볼 수 있는 화면이에요. 관리자 권한을 받았다면 로그아웃했다가 다시 로그인해 주세요.');
    return true;
  }
  return false;
}

const date = (s) => (s ? s.slice(0, 10).replaceAll('-', '.') : '');
const dateTime = (s) => (s ? `${date(s)} ${s.slice(11, 16)}` : '');

function statusText(u) {
  if (u.banned) return '강제 탈퇴';
  return STATUS_LABELS[u.status] ?? u.status;
}

function badges(u) {
  return [
    el('span', { class: `chip-count${u.status === 'ACTIVE' ? '' : ' st-rejected'}`, text: statusText(u) }),
    u.role === 'ADMIN' ? el('span', { class: 'chip-count', text: '관리자' }) : null,
    el('span', { class: 'type-badge', text: PROVIDER_LABELS[u.provider] ?? u.provider }),
  ];
}

function row(u) {
  const name = u.nickname ?? `탈퇴한 회원 ${u.id}`;
  const open = el('button', { type: 'button', class: 'member-open', 'aria-haspopup': 'dialog' },
    el('span', { class: 'member-name', text: name }),
    el('span', { class: 'sr-only', text: ' 회원 정보 열기' }));
  open.addEventListener('click', () => openDetail(u.id, open));
  return el('li', { class: 'member-row', 'data-id': String(u.id) },
    el('div', { class: 'member-main' }, open, el('span', { class: 'member-badges' }, ...badges(u))),
    el('p', { class: 'card-meta' },
      `#${u.id}`, u.email ? ` · ${u.email}` : '',
      ` · 가입 ${date(u.createdAt)}`,
      u.lastLoginAt ? ` · 최근 로그인 ${date(u.lastLoginAt)}` : '',
      ` · 글 ${u.posts.toLocaleString()} · 댓글 ${u.comments.toLocaleString()}`));
}

function emptyText() {
  if (state.q) return `‘${state.q}’에 맞는 회원이 없어요.`;
  return { '': '회원이 없어요.', ACTIVE: '활동 중인 회원이 없어요.', SUSPENDED: '작성 정지된 회원이 없어요.', BANNED: '강제 탈퇴한 회원이 없어요.', WITHDRAWN: '탈퇴한 회원이 없어요.', ADMIN: '관리자가 없어요.' }[state.tab];
}

async function load({ append = false, focusPanel = false } = {}) {
  const page = append ? state.page + 1 : 0;
  $('listStatus').textContent = '불러오는 중...';
  $('more').disabled = true;
  try {
    const data = await api.list(state.q, state.tab, page);
    state.page = page;
    COUNT_KEYS.forEach((k) => { $(`count-${k}`).textContent = (data.counts[k] ?? 0).toLocaleString(); });
    const firstNew = append ? state.items.length : 0;
    state.items = append ? state.items.concat(data.items) : data.items;
    if (append) $('list').append(...data.items.map(row));
    else $('list').replaceChildren(...data.items.map(row));
    $('listStatus').textContent = state.items.length
      ? `${state.items.length.toLocaleString()}명${data.hasMore ? ' (더 있어요)' : ''}${state.q ? ` · ‘${state.q}’ 찾음` : ''}`
      : emptyText();
    $('more').hidden = !data.hasMore;
    $('more').disabled = false;
    if (append) $('list').children[firstNew]?.querySelector('.member-open')?.focus();
    else if (focusPanel) $('panel').focus();
  } catch (e) {
    $('more').disabled = false;
    if (!handleAuthError(e)) $('listStatus').textContent = e.message || '목록을 불러오지 못했어요.';
  }
}

function selectTab(next, { focus = false } = {}) {
  state.tab = next;
  document.querySelectorAll('[role="tab"]').forEach((b) => {
    const on = b.dataset.tab === next;
    b.setAttribute('aria-selected', String(on));
    b.tabIndex = on ? 0 : -1;
    if (on && focus) b.focus();
  });
  $('panel').setAttribute('aria-labelledby', `tab-${next || 'ALL'}`);
  load();
}

// ---------- 회원 창 ----------
function info(label, value) {
  return el('div', {}, el('dt', { text: label }), el('dd', { text: value }));
}

function dialogAlert(message) {
  const a = $('dialogAlert');
  a.textContent = message ?? '';
  a.hidden = !message;
  if (message) a.focus();
}

function render(d) {
  state.current = d;
  const u = d.user;
  const withdrawn = u.status === 'WITHDRAWN';
  $('dialogTitle').textContent = u.nickname ?? `탈퇴한 회원 #${u.id}`;
  const rows = [
    info('상태', [statusText(u), u.role === 'ADMIN' ? '관리자' : ''].filter(Boolean).join(' · ')),
    info('회원 번호', `#${u.id}`),
    info('가입 방법', PROVIDER_LABELS[u.provider] ?? u.provider),
    info('이메일', d.fullEmail ?? (withdrawn ? '탈퇴 때 지움' : '없음')),
    info('가입', dateTime(u.createdAt)),
    info('최근 로그인', u.lastLoginAt ? dateTime(u.lastLoginAt) : '기록 없음'),
    info('약관 동의', d.agreedAt ? dateTime(d.agreedAt) : '아직 안 함'),
    info('쓴 것', `글 ${u.posts.toLocaleString()} · 댓글 ${u.comments.toLocaleString()} · 영업 정보 제보 ${d.storeReports.toLocaleString()}`),
    info('받은 신고', `${d.reportsAgainst.toLocaleString()}건 (글·댓글)`),
  ];
  if (d.ban) {
    rows.push(info('강제 탈퇴', `${dateTime(d.ban.at)}${d.ban.bannedBy ? ` · ${d.ban.bannedBy}` : ''}`));
    rows.push(info('탈퇴 까닭', d.ban.reason ?? ''));
  }
  $('memberInfo').replaceChildren(...rows);

  $('memberPosts').replaceChildren(...d.recentPosts.map((p) => el('li', {},
    el('a', { href: `/post.html?id=${p.id}`, target: '_blank', rel: 'noopener', text: p.title }),
    el('span', { class: 'meta', text: ` · ${POST_STATUS[p.status] ?? p.status} · ${date(p.createdAt)}` }))));
  $('noPosts').hidden = d.recentPosts.length > 0;

  const canAct = !withdrawn && u.role !== 'ADMIN' && u.id !== state.me;
  $('suspendBox').hidden = !canAct;
  if (canAct) {
    const on = u.status === 'SUSPENDED';
    $('suspendButton').textContent = on ? '작성 정지 풀기' : '작성 정지';
    $('suspendButton').disabled = false;
  }
  $('withdrawBox').hidden = !canAct;
  $('withdrawReason').value = '';
  $('withdrawReason').removeAttribute('aria-invalid');
  $('reasonError').textContent = '';
  $('withdrawConfirm').checked = false;
  $('withdrawButton').disabled = true;
  $('rejoinBox').hidden = !u.banned;
  $('rejoinButton').disabled = false;
}

async function openDetail(id, opener) {
  state.opener = opener;
  dialogAlert(null);
  try {
    render(await api.detail(id));
    if (!$('memberDialog').open) $('memberDialog').showModal();
    $('closeDialog').focus();
  } catch (e) {
    if (!handleAuthError(e)) toast(e.message || '회원 정보를 불러오지 못했어요.');
  }
}

function closeDialog() {
  $('memberDialog').close();
}

// 처리 뒤 목록에서 그 줄만 바꿔 끼움 (탭 조건에서 빠지면 다음 새로고침 때 사라짐)
function replaceRow(u) {
  const li = $('list').querySelector(`[data-id="${u.id}"]`);
  if (!li) return;
  const fresh = row(u);
  li.replaceWith(fresh);
  if (state.opener && !document.contains(state.opener)) state.opener = fresh.querySelector('.member-open');
}

async function act(button, work, message, { refreshCounts = true } = {}) {
  button.disabled = true;
  dialogAlert(null);
  try {
    const result = await work();
    const d = result?.user ? result : await api.detail(state.current.user.id);
    render(d);
    replaceRow(d.user);
    toast(message);
    if (refreshCounts) {
      api.list(state.q, state.tab, 0).then((data) => {
        COUNT_KEYS.forEach((k) => { $(`count-${k}`).textContent = (data.counts[k] ?? 0).toLocaleString(); });
      }).catch(() => {});
    }
    $('closeDialog').focus();
  } catch (e) {
    button.disabled = false;
    if (!handleAuthError(e)) dialogAlert(e.message || '처리하지 못했어요. 잠시 뒤 다시 해 주세요.');
  }
}

function setupDialog() {
  const dialog = $('memberDialog');
  $('closeDialog').addEventListener('click', closeDialog);
  $('cancelButton').addEventListener('click', closeDialog);
  dialog.addEventListener('close', () => {
    const o = state.opener;
    if (o && document.contains(o)) o.focus();
  });
  dialog.addEventListener('click', (e) => { if (e.target === dialog) closeDialog(); });

  $('suspendButton').addEventListener('click', () => {
    const u = state.current.user;
    const on = u.status !== 'SUSPENDED';
    act($('suspendButton'), () => api.suspend(u.id, on),
      on ? `${u.nickname}님의 글쓰기를 멈췄어요.` : `${u.nickname}님의 작성 정지를 풀었어요.`);
  });

  $('withdrawConfirm').addEventListener('change', () => {
    $('withdrawButton').disabled = !$('withdrawConfirm').checked;
  });
  $('withdrawReason').addEventListener('input', () => {
    if ($('reasonError').textContent) { $('reasonError').textContent = ''; $('withdrawReason').removeAttribute('aria-invalid'); }
  });
  $('withdrawButton').addEventListener('click', () => {
    const reason = $('withdrawReason').value.trim();
    if (reason.length < 5) {
      $('reasonError').textContent = '탈퇴시키는 까닭을 5자 넘게 적어 주세요. 본인에게 메일로 가요.';
      $('withdrawReason').setAttribute('aria-invalid', 'true');
      $('withdrawReason').focus();
      return;
    }
    const u = state.current.user;
    const name = u.nickname;
    act($('withdrawButton'), () => api.withdraw(u.id, reason),
      `${name}님을 강제 탈퇴시켰어요. 글·댓글을 가리고 다시 가입을 막았어요.`);
  });

  $('rejoinButton').addEventListener('click', () => {
    const u = state.current.user;
    act($('rejoinButton'), () => api.rejoin(u.id), '이제 같은 이메일·카카오 계정으로 다시 가입할 수 있어요.');
  });
}

async function init() {
  mountAccount($('account'), { onLogout: () => location.replace('/') });
  const user = await auth.init();
  if (!user) { location.replace(auth.loginUrl(PAGE)); return; }
  if (user.role !== 'ADMIN') { showGate('관리자만 볼 수 있는 화면이에요.'); return; }
  state.me = user.id;
  $('gate').hidden = true;
  $('workspace').hidden = false;

  const tabs = [...document.querySelectorAll('[role="tab"]')];
  tabs.forEach((b) => {
    b.addEventListener('click', () => selectTab(b.dataset.tab));
    b.addEventListener('keydown', (event) => {
      const i = tabs.indexOf(b);
      const next = { ArrowRight: (i + 1) % tabs.length, ArrowLeft: (i - 1 + tabs.length) % tabs.length, Home: 0, End: tabs.length - 1 }[event.key];
      if (next === undefined) return;
      event.preventDefault();
      selectTab(tabs[next].dataset.tab, { focus: true });
    });
  });
  $('searchForm').addEventListener('submit', (e) => {
    e.preventDefault();
    state.q = $('q').value.trim();
    load({ focusPanel: false });
  });
  $('more').addEventListener('click', () => load({ append: true }));
  setupDialog();

  const id = new URLSearchParams(location.search).get('id');
  if (id && /^\d+$/.test(id)) { state.q = id; $('q').value = id; }
  await load();
}

init();
