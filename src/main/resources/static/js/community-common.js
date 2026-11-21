// 동네 이야기 게시판 공통 (COM-01): API 호출, 날짜·작성자 표시, 요소 만들기
// 글·댓글은 언제나 textContent 로 넣는다 (HTML로 해석하지 않음)
import * as auth from './auth.js';

export const CATEGORIES = [
  { code: 'REVIEW', label: '후기' },
  { code: 'RECOMMEND', label: '추천' },
  { code: 'QUESTION', label: '질문' },
  { code: 'FREE', label: '자유' },
];
export const CATEGORY_HELP = {
  REVIEW: '다녀온 가게·시장·축제 이야기',
  RECOMMEND: '이음에 없는 동네 가게·시장을 알려 주세요',
  QUESTION: '주차, 장날, 영업시간 같은 궁금한 점',
  FREE: '동네 이야기 무엇이든',
};
export const REPORT_REASONS = [
  { code: 'SPAM', label: '광고·도배' },
  { code: 'ABUSE', label: '욕설·비방' },
  { code: 'PRIVACY', label: '개인정보 노출' },
  { code: 'FALSE', label: '사실과 다른 내용' },
  { code: 'ETC', label: '기타' },
];
export const PLACE_LABEL = { STORE: '가게', MARKET: '시장', FESTIVAL: '축제' };

export function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([key, value]) => {
    if (value === null || value === undefined || value === false) return;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value === true ? '' : value);
  });
  children.flat().filter((c) => c !== null && c !== undefined && c !== false).forEach((c) => node.append(c));
  return node;
}

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message ?? '잠시 후 다시 시도해 주세요.');
    this.status = status;
    this.code = body?.code ?? `HTTP_${status}`;
    this.details = body?.details ?? [];
  }
}

/** 로그인했으면 토큰을 붙여서 (보기 API도 '내 글', '공감했는지'를 알려고) */
export async function request(url, options = {}) {
  const res = await auth.authFetch(url, {
    ...options,
    headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
  });
  if (res.status === 204) return null;
  const body = await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(res.status, body);
  return body;
}

export const posts = {
  list: ({ category = '', q = '', placeType = '', placeId = '', page = 0 } = {}) =>
    request(`/api/posts?${new URLSearchParams({ category, q, placeType, placeId: placeId ?? '', page })}`),
  get: (id) => request(`/api/posts/${encodeURIComponent(id)}`),
  create: (body) => request('/api/posts', { method: 'POST', body: JSON.stringify(body) }),
  update: (id, body) => request(`/api/posts/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) }),
  remove: (id) => request(`/api/posts/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  like: (id, on) => request(`/api/posts/${encodeURIComponent(id)}/like`, { method: on ? 'POST' : 'DELETE' }),
  comment: (id, content) => request(`/api/posts/${encodeURIComponent(id)}/comments`, { method: 'POST', body: JSON.stringify({ content }) }),
  removeComment: (id) => request(`/api/comments/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  report: (body) => request('/api/community/reports', { method: 'POST', body: JSON.stringify(body) }),
  mine: () => request('/api/users/me/posts'),
  /** 사진 한 장 올리기 (COM-02). 본문이 사진 파일 그대로 → {id, width, height} */
  uploadPhoto: async (blob) => {
    const res = await auth.authFetch('/api/posts/photos', { method: 'POST', headers: { 'Content-Type': blob.type || 'image/jpeg' }, body: blob });
    const body = await res.json().catch(() => null);
    if (!res.ok) throw new ApiError(res.status, body);
    return body;
  },
};

/** '방금', '3분 전', '오늘 14:05', '2026.11.20' */
export function when(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  const diff = (Date.now() - d.getTime()) / 60000;
  if (diff < 1) return '방금';
  if (diff < 60) return `${Math.floor(diff)}분 전`;
  const today = new Date();
  if (d.toDateString() === today.toDateString()) {
    return `오늘 ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  }
  return iso.slice(0, 10).replaceAll('-', '.');
}

export function categoryBadge(code, label) {
  return el('span', { class: `cat-badge cat-${(code ?? '').toLowerCase()}`, text: label });
}

export function authorText(a) {
  return a?.mine ? `${a.nickname} (나)` : (a?.nickname ?? '');
}

export function placeLink(place) {
  if (!place) return null;
  const label = `${PLACE_LABEL[place.type] ?? ''} · ${place.name}`;
  return place.url
    ? el('a', { class: 'place-chip', href: place.url, text: label })
    : el('span', { class: 'place-chip', text: label });
}

/** 글 목록의 한 줄 */
export function postCard(p, { showStatus = false } = {}) {
  const meta = [authorText(p.author), when(p.createdAt), `공감 ${p.likeCount}`, `댓글 ${p.commentCount}`,
    p.photoCount ? `사진 ${p.photoCount}` : null].filter(Boolean).join(' · ');
  const status = showStatus && p.status !== 'VISIBLE'
    ? el('span', { class: 'state-badge', text: p.status === 'DELETED' ? '지움' : '가려짐' }) : null;
  const rec = p.recommendStatusLabel ? el('span', { class: `rec-badge rec-${(p.recommendStatus ?? '').toLowerCase()}`, text: p.recommendStatusLabel }) : null;
  const parts = [
    el('div', { class: 'post-card-head' }, categoryBadge(p.category, p.categoryLabel), status, rec,
      el('a', { class: 'post-card-title', href: `/post.html?id=${p.id}`, text: p.title })),
    p.excerpt ? el('p', { class: 'post-card-excerpt', text: p.excerpt }) : null,
    el('p', { class: 'post-card-meta' }, meta, p.edited ? ' · 고침' : null),
    p.place ? el('p', { class: 'post-card-place' }, placeLink(p.place)) : null];
  if (!p.thumbnail) return el('li', { class: 'post-card' }, parts);
  // 첫 사진을 오른쪽에 작게 (COM-02). 제목 링크가 같은 곳으로 가므로 그림은 꾸밈(alt="")
  return el('li', { class: 'post-card has-photo' },
    el('div', { class: 'post-card-main' }, parts),
    el('img', { class: 'post-card-thumb', src: p.thumbnail, alt: '', loading: 'lazy', decoding: 'async', width: '88', height: '88' }));
}

export function toast(message, ms = 4000) {
  let box = document.getElementById('toast');
  if (!box) {
    box = el('p', { id: 'toast', class: 'toast', role: 'status' });
    document.body.append(box);
  }
  box.textContent = message;
  box.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { box.hidden = true; }, ms);
}
