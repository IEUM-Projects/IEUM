// 동네 소식 (NEWS-01): 기사 목록 한 줄 (제목·출처 도메인·날짜, 원문은 새 창). 본문은 보여 주지 않는다.

export const NEWS_TOPICS = [
  { key: 'market', label: '전통시장' },
  { key: 'store', label: '백년가게' },
  { key: 'local', label: '로컬크리에이터' },
];

function make(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([key, value]) => {
    if (value === null || value === undefined || value === false) return;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value === true ? '' : value);
  });
  children.flat().filter((c) => c !== null && c !== undefined && c !== false && c !== '').forEach((c) => node.append(c));
  return node;
}

/** '2026-11-17T11:00:00' → '2026.11.17' (없으면 빈칸) */
export function newsDate(iso) {
  return iso ? iso.slice(0, 10).replaceAll('-', '.') : '';
}

export function newsItem(n) {
  return make('li', { class: 'news-item' },
    make('a', { class: 'news-link', href: n.url, target: '_blank', rel: 'noopener noreferrer' },
      make('span', { class: 'news-title', text: n.title }),
      make('span', { class: 'sr-only', text: ' (새 창)' })),
    make('span', { class: 'news-meta' },
      make('span', { text: n.host }),
      n.publishedAt ? make('time', { datetime: n.publishedAt, text: newsDate(n.publishedAt) }) : null));
}

export const NEWS_NOTE = '네이버 뉴스 검색으로 찾은 기사 제목이에요. 제목을 누르면 언론사 원문이 새 창으로 열려요.';
