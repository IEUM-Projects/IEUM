// 공지 목록 (NOT-01): /notices.html?page=2  (쪽 번호는 1부터)
import { mountAccount } from './header.js';
import * as auth from './auth.js';
import { notices, noticeDate } from './notice-format.js';

const $ = (id) => document.getElementById(id);
const PAGE_SIZE = 10;

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([key, value]) => {
    if (value === null || value === undefined || value === false) return;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value);
  });
  children.flat().filter((c) => c !== null && c !== undefined && c !== false).forEach((child) => node.append(child));
  return node;
}

function pageFromAddress() {
  const n = Number(new URLSearchParams(location.search).get('page'));
  return Number.isInteger(n) && n >= 1 ? n : 1;
}

function row(n) {
  return el('li', {},
    el('a', { href: `/notice.html?id=${n.id}`, class: 'notice-row' },
      n.pinned ? el('span', { class: 'pin-badge', text: '고정' }) : null,
      el('span', { class: 'notice-title', text: n.title }),
      el('time', { datetime: n.createdAt?.slice(0, 10), text: noticeDate(n.createdAt) })));
}

function pager(page, totalPages) {
  const nav = $('pager');
  if (totalPages <= 1) { nav.hidden = true; return; }
  const link = (p, label, extra = {}) => el('a', { href: `/notices.html?page=${p}`, ...extra }, label);
  const items = [];
  if (page > 1) items.push(link(page - 1, '이전', { class: 'pager-step', 'aria-label': '이전 쪽' }));
  // 지금 쪽 앞뒤로 2쪽씩
  const from = Math.max(1, page - 2);
  const to = Math.min(totalPages, page + 2);
  for (let p = from; p <= to; p++) {
    items.push(p === page
      ? el('span', { class: 'pager-current', 'aria-current': 'page', 'aria-label': `${p}쪽, 지금 쪽`, text: String(p) })
      : link(p, String(p), { 'aria-label': `${p}쪽` }));
  }
  if (page < totalPages) items.push(link(page + 1, '다음', { class: 'pager-step', 'aria-label': '다음 쪽' }));
  nav.replaceChildren(...items);
  nav.hidden = false;
}

async function init() {
  mountAccount($('account'));
  auth.init();
  const page = pageFromAddress();
  try {
    const data = await notices.list(page - 1, PAGE_SIZE);
    $('noticeList').replaceChildren(...data.items.map(row));
    $('listStatus').textContent = data.total === 0 ? '아직 공지가 없어요.'
      : data.items.length === 0 ? '이 쪽에는 공지가 없어요.' : `공지 ${data.total}개 · ${page}/${data.totalPages}쪽`;
    if (page > 1) document.title = `공지사항 ${page}쪽 - 이음`;
    pager(page, data.totalPages);
  } catch (_) {
    $('listStatus').textContent = '공지를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
}

init();
