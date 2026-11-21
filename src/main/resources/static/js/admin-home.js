// 관리자 요약 (ADM-03): 처리할 일, 최근 7일 숫자, 최근 관리자 작업
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { el, request } from './community-common.js';
import { actionRow } from './admin-actions.js';

const $ = (id) => document.getElementById(id);
const PAGE = '/admin-home.html';

function showGate(message) {
  $('gate').textContent = message;
  $('gate').hidden = false;
  $('workspace').hidden = true;
}

const dayLabel = (iso) => {
  const d = new Date(`${iso}T00:00:00`);
  return `${d.getMonth() + 1}.${d.getDate()} (${'일월화수목금토'[d.getDay()]})`;
};

function todoCard(t) {
  const zero = t.count === 0;
  return el('li', {},
    el('a', { class: `todo-card${zero ? ' is-zero' : ''}`, href: t.url },
      el('span', { class: 'todo-label', text: t.label }),
      el('span', { class: 'todo-count' }, el('strong', { text: t.count.toLocaleString() }), '건'),
      el('span', { class: 'todo-note', text: zero ? '없음' : (t.note ?? '') })));
}

function render(s) {
  $('todoTotal').textContent = s.todoTotal.toLocaleString();
  $('todoList').replaceChildren(...s.todos.map(todoCard));

  const keys = ['signups', 'withdrawals', 'posts', 'comments', 'storeReports', 'communityReports'];
  const sum = Object.fromEntries(keys.map((k) => [k, s.days.reduce((a, d) => a + d[k], 0)]));
  // 오늘을 맨 위로
  $('weekBody').replaceChildren(...[...s.days].reverse().map((d, i) => el('tr', i === 0 ? { class: 'is-today' } : {},
    el('th', { scope: 'row', text: i === 0 ? `오늘 ${dayLabel(d.date)}` : dayLabel(d.date) }),
    ...keys.map((k) => el('td', { text: d[k].toLocaleString() })))));
  $('weekFoot').replaceChildren(el('tr', {}, el('th', { scope: 'row', text: '7일 합계' }), ...keys.map((k) => el('td', { text: sum[k].toLocaleString() }))));
  const t = s.totals;
  $('totals').textContent = `지금 회원 ${t.members.toLocaleString()}명 (작성 정지 ${t.suspended.toLocaleString()}명) · 보이는 글 ${t.posts.toLocaleString()}개 · 가려진 글 ${t.hidden.toLocaleString()}개`;

  $('recentList').replaceChildren(...s.recent.map(actionRow));
  $('recentEmpty').hidden = s.recent.length > 0;
}

async function init() {
  mountAccount($('account'), { onLogout: () => location.replace('/') });
  const user = await auth.init();
  if (!user) { location.replace(auth.loginUrl(PAGE)); return; }
  if (user.role !== 'ADMIN') { showGate('관리자만 볼 수 있는 화면이에요.'); return; }
  try {
    render(await request('/api/admin/summary'));
    $('gate').hidden = true;
    $('workspace').hidden = false;
  } catch (e) {
    if (e.status === 401) { location.replace(auth.loginUrl(PAGE)); return; }
    showGate(e.status === 403 ? '관리자만 볼 수 있는 화면이에요. 관리자 권한을 받았다면 로그아웃했다가 다시 로그인해 주세요.' : '요약을 불러오지 못했어요. 잠시 후 다시 열어 주세요.');
  }
}

init();
