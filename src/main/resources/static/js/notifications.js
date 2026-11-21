// 알림 화면 (NOTI-01): 최근 순 20개씩, 안 읽은 알림은 굵게·점. 누르면 읽음으로 하고 그 화면으로 간다.
import * as auth from './auth.js';
import { mountAccount } from './header.js';

const $ = (id) => document.getElementById(id);
const KIND = {
  COMMENT: '댓글', REPORT_APPLIED: '제보', REPORT_REJECTED: '제보', HIDDEN: '가림', RESTORED: '다시 보임', RECOMMEND: '추천',
};
let page = 0;

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([k, v]) => {
    if (k === 'class') node.className = v;
    else if (k === 'text') node.textContent = v;
    else node.setAttribute(k, v);
  });
  children.flat().filter(Boolean).forEach((c) => node.append(c));
  return node;
}

/** 방금 · 5분 전 · 3시간 전 · 어제 · 11월 3일 */
function when(iso) {
  const t = new Date(iso);
  const sec = (Date.now() - t.getTime()) / 1000;
  if (sec < 60) return '방금';
  if (sec < 3600) return `${Math.floor(sec / 60)}분 전`;
  const today = new Date();
  const days = Math.round((new Date(today.toDateString()) - new Date(t.toDateString())) / 86400000);
  if (days === 0) return `${Math.floor(sec / 3600)}시간 전`;
  if (days === 1) return '어제';
  return `${t.getMonth() + 1}월 ${t.getDate()}일`;
}

/** 서버가 준 이음 안의 주소만 쓴다 (다른 사이트로 가는 주소는 막음) */
function safeLink(link) {
  return typeof link === 'string' && link.startsWith('/') && !link.startsWith('//') ? link : '/';
}

function changed() {
  window.dispatchEvent(new Event('ieum-notifications'));   // 위쪽 종의 수를 다시 센다
}

function item(n) {
  const li = el('li', { class: `noti-item${n.read ? '' : ' unread'}` });
  const a = el('a', { class: 'noti-row', href: safeLink(n.link) },
    el('span', { class: 'noti-dot', 'aria-hidden': 'true' }),
    el('span', { class: 'noti-text' },
      el('span', { class: 'noti-top' },
        el('span', { class: 'noti-kind', text: KIND[n.type] ?? '알림' }),
        el('time', { class: 'noti-time', datetime: n.at, text: when(n.at) })),
      el('strong', { class: 'noti-title' }, n.read ? null : el('span', { class: 'sr-only', text: '안 읽음, ' }), n.title),
      n.body ? el('span', { class: 'noti-body', text: n.body }) : null));
  a.addEventListener('click', async (event) => {
    if (li.classList.contains('unread')) {
      event.preventDefault();
      try { await auth.authFetch(`/api/notifications/${n.id}/read`, { method: 'POST' }); } catch (_) { /* 못 해도 이동 */ }
      location.href = a.getAttribute('href');
    }
  });
  li.append(a);
  return li;
}

async function load() {
  $('more').disabled = true;
  try {
    const res = await auth.authFetch(`/api/notifications?page=${page}`);
    if (!res.ok) throw new Error();
    const data = await res.json();
    $('notiList').append(...data.items.map(item));
    $('more').hidden = !data.hasMore;
    $('readAll').hidden = data.unread === 0;
    const total = $('notiList').children.length;
    $('listStatus').textContent = total ? (data.unread ? `안 읽은 알림 ${data.unread}개` : '') : '아직 알림이 없어요. 내 글에 댓글이 달리거나 제보가 처리되면 여기에 알려 드려요.';
  } catch (_) {
    $('listStatus').textContent = '알림을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  } finally {
    $('more').disabled = false;
  }
}

async function init() {
  mountAccount($('account'), { onLogout: () => location.replace('/') });
  const user = await auth.init();
  if (!user) {
    location.replace(auth.loginUrl('/notifications.html'));
    return;
  }
  await load();
  $('more').addEventListener('click', () => { page += 1; load(); });
  $('readAll').addEventListener('click', async () => {
    $('readAll').disabled = true;
    try {
      const res = await auth.authFetch('/api/notifications/read-all', { method: 'POST' });
      if (!res.ok) throw new Error();
      document.querySelectorAll('.noti-item.unread').forEach((li) => {
        li.classList.remove('unread');
        li.querySelector('.noti-title .sr-only')?.remove();
      });
      $('readAll').hidden = true;
      $('listStatus').textContent = '모두 읽음으로 바꿨어요.';
      changed();
      $('notiList').querySelector('a')?.focus();
    } catch (_) {
      $('listStatus').textContent = '잠시 후 다시 시도해 주세요.';
    } finally {
      $('readAll').disabled = false;
    }
  });
}

init();
