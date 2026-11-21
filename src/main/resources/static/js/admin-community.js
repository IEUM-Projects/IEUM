// 관리자: 동네 이야기 관리 (COM-01) - 신고 접수, 가린 글·댓글, 가게 추천
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { el, request } from './community-common.js';

const $ = (id) => document.getElementById(id);
const TABS = ['REPORTED', 'HIDDEN', 'RECOMMEND'];
const STATUS_LABELS = { VISIBLE: '보이는 중', HIDDEN: '가림', TEMP_HIDDEN: '임시 숨김', DELETED: '지움' };
const REC_LABELS = { PENDING: '확인 전', ADDED: '지도에 추가함', EXISTS: '이미 있는 곳', HOLD: '보류' };
const CAT_LABELS = { REVIEW: '후기', RECOMMEND: '추천', QUESTION: '질문', FREE: '자유' };
let tab = 'REPORTED';

const adminApi = {
  list: (t) => request(`/api/admin/community?tab=${t}`),
  hide: (type, id, body) => request(`/api/admin/community/${type}/${id}/hide`, { method: 'POST', body: JSON.stringify(body) }),
  restore: (type, id) => request(`/api/admin/community/${type}/${id}/restore`, { method: 'POST' }),
  recommend: (id, body) => request(`/api/admin/community/posts/${id}/recommend`, { method: 'PUT', body: JSON.stringify(body) }),
  suspend: (userId, on) => request(`/api/admin/users/${userId}/suspend`, { method: 'POST', body: JSON.stringify({ suspend: on }) }),
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
}

function handleAuthError(e) {
  if (e.status === 401) { location.replace(auth.loginUrl('/admin-community.html')); return true; }
  if (e.status === 403 && e.code !== 'WRITE_SUSPENDED') {
    showGate('관리자만 볼 수 있는 화면이에요. 관리자 권한을 받았다면 로그아웃했다가 다시 로그인해 주세요.');
    return true;
  }
  return false;
}

const dateTime = (s) => (s ? `${s.slice(0, 10).replaceAll('-', '.')} ${s.slice(11, 16)}` : '');

function button(text, label, onClick, cls = 'button-secondary') {
  const b = el('button', { type: 'button', class: cls, text, 'aria-label': label });
  b.addEventListener('click', () => onClick(b));
  return b;
}

async function run(trigger, work, message) {
  trigger.disabled = true;
  try {
    await work();
    toast(message);
    await load({ keepFocus: true });
  } catch (e) {
    trigger.disabled = false;
    if (!handleAuthError(e)) toast(e.message);
  }
}

function hideBox(t, name) {
  const boxId = `hide-${t.type}-${t.id}`;
  const reasonId = `${boxId}-reason`;
  const tempId = `${boxId}-temp`;
  const reason = el('input', { id: reasonId, type: 'text', maxlength: '200', value: t.reasons ? Object.keys(t.reasons).join(', ') : '' });
  const temp = el('input', { id: tempId, type: 'checkbox' });
  const box = el('div', { class: 'confirm-box', id: boxId, hidden: true },
    el('div', { class: 'field' }, el('label', { for: reasonId, text: '가리는 까닭 (글쓴이에게 보여요)' }), reason),
    el('label', { class: 'check', for: tempId }, temp, ' 임시 숨김 (30일, 권리 침해 신고일 때)'),
    button('가리기', `${name} 가리기`, (b) => run(b, () => adminApi.hide(t.type, t.id, { reason: reason.value.trim(), temp: temp.checked }), '가렸어요.'), 'button-danger'),
    button('그만두기', `${name} 가리기 그만두기`, () => { box.hidden = true; opener.setAttribute('aria-expanded', 'false'); opener.focus(); }));
  const opener = button('가리기…', `${name} 가리기`, (b) => {
    box.hidden = false;
    b.setAttribute('aria-expanded', 'true');
    reason.focus();
  }, 'button-primary');
  opener.setAttribute('aria-expanded', 'false');
  opener.setAttribute('aria-controls', boxId);
  return { opener, box };
}

function recommendBox(t) {
  const sel = el('select', { id: `rec-${t.id}` }, Object.entries(REC_LABELS).map(([k, v]) => el('option', { value: k, text: v, selected: k === (t.recommendStatus ?? 'PENDING') })));
  const note = el('input', { id: `recnote-${t.id}`, type: 'text', maxlength: '200', value: t.recommendNote ?? '' });
  return el('div', { class: 'confirm-box rec-box' },
    el('div', { class: 'field' }, el('label', { for: `rec-${t.id}`, text: '추천 처리' }), sel),
    el('div', { class: 'field' }, el('label', { for: `recnote-${t.id}`, text: '글쓴이에게 남길 말 (선택, 글에 보여요)' }), note),
    button('저장', `${t.title} 추천 처리 저장`, (b) => run(b, () => adminApi.recommend(t.id, { status: sel.value, note: note.value.trim() }), '추천 처리를 저장했어요.'), 'button-primary'));
}

function row(t) {
  const isPost = t.type === 'POST';
  const name = isPost ? `글 '${t.title}'` : `댓글 ${t.id}`;
  const top = el('div', { class: 'card-top' },
    el('span', { class: 'type-badge', text: isPost ? `글 · ${CAT_LABELS[t.category] ?? ''}` : '댓글' }),
    el('span', { class: `chip-count${t.status === 'VISIBLE' ? '' : ' st-rejected'}`, text: STATUS_LABELS[t.status] ?? t.status }),
    t.tempExpired ? el('span', { class: 'wait late', text: '임시 숨김 30일 지남 · 다시 판단해 주세요' }) : null,
    t.reportCount ? el('span', { class: 'chip-count', text: `신고 ${t.reportCount}` }) : null,
    t.recommendStatus ? el('span', { class: 'chip-count', text: REC_LABELS[t.recommendStatus] }) : null);
  const reasons = t.reasons && Object.keys(t.reasons).length
    ? el('p', { class: 'card-meta', text: `신고 까닭: ${Object.entries(t.reasons).map(([k, n]) => `${k} ${n}`).join(', ')}` }) : null;
  const notes = t.reportNotes?.length
    ? el('ul', { class: 'card-reports' }, t.reportNotes.map((n) => el('li', { class: 'report-content', text: n }))) : null;
  const actions = el('div', { class: 'row-actions' });
  let extra = null;
  if (t.status === 'VISIBLE') {
    const { opener, box } = hideBox(t, name);
    actions.append(opener);
    extra = box;
    if (t.reportCount) actions.append(button('문제없음', `${name} 신고 문제없음`, (b) => run(b, () => adminApi.restore(t.type, t.id), '신고를 문제없음으로 닫았어요.')));
  } else if (t.status === 'HIDDEN' || t.status === 'TEMP_HIDDEN') {
    actions.append(button('다시 보이기', `${name} 다시 보이기`, (b) => run(b, () => adminApi.restore(t.type, t.id), '다시 보이게 했어요.')));
  }
  if (t.authorId && t.authorStatus === 'ACTIVE') {
    actions.append(button('글쓴이 작성 정지', `${t.author} 작성 정지`, (b) => run(b, () => adminApi.suspend(t.authorId, true), `${t.author}님의 글쓰기를 멈췄어요.`)));
  } else if (t.authorId && t.authorStatus === 'SUSPENDED') {
    actions.append(button('작성 정지 풀기', `${t.author} 작성 정지 풀기`, (b) => run(b, () => adminApi.suspend(t.authorId, false), `${t.author}님의 작성 정지를 풀었어요.`)));
  }
  return el('li', { class: 'admin-card', 'data-key': `${t.type}-${t.id}` },
    top,
    isPost ? el('h2', { class: 'card-title', text: t.title }) : null,
    el('p', { class: 'note', text: t.content ?? '' }),
    el('p', { class: 'card-meta' },
      `${t.author}${t.authorStatus === 'SUSPENDED' ? ' (작성 정지)' : ''} · ${dateTime(t.createdAt)}`,
      t.hiddenReason ? ` · 가린 까닭: ${t.hiddenReason}` : '',
      t.hiddenUntil ? ` · 임시 숨김 ${t.hiddenUntil.replaceAll('-', '.')}까지` : '',
      ' · ', el('a', { href: `/post.html?id=${t.postId ?? t.id}`, target: '_blank', rel: 'noopener', text: isPost ? '글 보기' : '댓글 달린 글 보기' })),
    reasons, notes,
    // 글의 사진 (COM-02): 작게 보이고, 누르면 새 탭에 크게
    t.photos?.length ? el('ul', { class: 'card-photos', 'aria-label': `사진 ${t.photos.length}장` },
      t.photos.map((ph, i) => el('li', {}, el('a', { href: ph.url, target: '_blank', rel: 'noopener' },
        el('img', { src: ph.thumbUrl, alt: `사진 ${i + 1} 크게 보기${ph.alt ? ` (${ph.alt})` : ''}`, width: '72', height: '72', loading: 'lazy' }))))) : null,
    tab === 'RECOMMEND' ? recommendBox(t) : null,
    actions, extra);
}

async function load({ keepFocus = false } = {}) {
  $('listStatus').textContent = '불러오는 중...';
  try {
    const data = await adminApi.list(tab);
    TABS.forEach((k) => { $(`count-${k}`).textContent = (data.counts[k] ?? 0).toLocaleString(); });
    $('list').replaceChildren(...data.items.map(row));
    $('listStatus').textContent = data.items.length
      ? `${data.items.length.toLocaleString()}건`
      : { REPORTED: '처리할 신고가 없어요.', HIDDEN: '가린 글·댓글이 없어요.', RECOMMEND: '가게 추천 글이 없어요.' }[tab];
    if (keepFocus) $('panel').focus();
  } catch (e) {
    if (!handleAuthError(e)) $('listStatus').textContent = '목록을 불러오지 못했어요.';
  }
}

function selectTab(next, { focus = false } = {}) {
  tab = next;
  document.querySelectorAll('[role="tab"]').forEach((b) => {
    const on = b.dataset.tab === next;
    b.setAttribute('aria-selected', String(on));
    b.tabIndex = on ? 0 : -1;
    if (on && focus) b.focus();
  });
  $('panel').setAttribute('aria-labelledby', `tab-${next}`);
  load();
}

async function init() {
  mountAccount($('account'), { onLogout: () => location.replace('/') });
  const user = await auth.init();
  if (!user) { location.replace(auth.loginUrl('/admin-community.html')); return; }
  if (user.role !== 'ADMIN') { showGate('관리자만 볼 수 있는 화면이에요.'); return; }
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
  load();
}

init();
