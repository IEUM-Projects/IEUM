// 공지 하나 (NOT-01): /notice.html?id=3
// 내용은 HTML이 아니라 글자로만 넣는다 (textContent). 줄바꿈은 CSS(white-space: pre-line)로 살린다.
import { mountAccount } from './header.js';
import * as auth from './auth.js';
import { notices, noticeDate } from './notice-format.js';

const main = document.getElementById('main');

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

function fail(message) {
  main.replaceChildren(
    el('h1', { text: '공지사항' }),
    el('p', { class: 'list-status', role: 'status', text: message }),
    el('a', { class: 'button-secondary', href: '/notices.html', text: '공지 목록으로' }));
}

async function init() {
  mountAccount(document.getElementById('account'));
  auth.init();
  const id = new URLSearchParams(location.search).get('id');
  if (!/^\d{1,18}$/.test(id ?? '')) {
    fail('공지를 찾을 수 없어요.');
    return;
  }
  try {
    const n = await notices.get(id);
    document.title = `${n.title} - 공지사항 - 이음`;
    main.replaceChildren(el('article', { class: 'notice-article' },
      el('header', { class: 'article-head' },
        n.pinned ? el('span', { class: 'pin-badge', text: '고정' }) : null,
        el('h1', { text: n.title }),
        el('p', { class: 'article-date' },
          el('time', { datetime: n.createdAt.slice(0, 10), text: noticeDate(n.createdAt) }),
          n.edited ? ` · ${noticeDate(n.updatedAt)} 수정` : null)),
      el('div', { class: 'article-body', text: n.content })),
    el('a', { class: 'button-secondary list-button', href: '/notices.html', text: '공지 목록으로' }));
  } catch (e) {
    fail(e.status === 404 ? '공지를 찾을 수 없어요. 내려간 공지일 수 있어요.' : '공지를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.');
  }
}

init();
