// 검색 공통 (SRC-01): 최근 검색어, 검색어 강조
const RECENT_KEY = 'ieum_recent_searches';   // 이 브라우저에만 기억 (편의 기능)
const RECENT_MAX = 8;

export const SEARCH_EXAMPLES = ['냉면', '한복', '전주', '5일장', '불꽃축제'];

export function recentSearches() {
  try {
    const list = JSON.parse(localStorage.getItem(RECENT_KEY));
    return Array.isArray(list) ? list.filter((s) => typeof s === 'string' && s.length <= 30).slice(0, RECENT_MAX) : [];
  } catch (_) {
    return [];
  }
}

function saveRecent(list) {
  try { localStorage.setItem(RECENT_KEY, JSON.stringify(list.slice(0, RECENT_MAX))); } catch (_) { /* 저장이 안 되면 기억하지 않음 */ }
}

export function addRecent(q) {
  saveRecent([q, ...recentSearches().filter((s) => s !== q)]);
}

export function removeRecent(q) {
  saveRecent(recentSearches().filter((s) => s !== q));
}

export function clearRecent() {
  saveRecent([]);
}

/** 검색어 정리: 공백 정리, 30자까지 */
export function cleanQuery(raw) {
  return (raw ?? '').replace(/\s+/g, ' ').trim().slice(0, 30);
}

/**
 * text 안에서 낱말들을 <mark>로 감싼 노드 목록 (HTML로 넣지 않고 글자 노드로만 만든다)
 */
export function highlight(text, words) {
  const source = text ?? '';
  const ws = words.filter(Boolean).map((w) => w.toLowerCase());
  if (!ws.length || !source) return [document.createTextNode(source)];
  const lower = source.toLowerCase();
  const marks = [];
  ws.forEach((w) => {
    let i = lower.indexOf(w);
    while (i !== -1) { marks.push([i, i + w.length]); i = lower.indexOf(w, i + w.length); }
  });
  marks.sort((a, b) => a[0] - b[0]);
  const nodes = [];
  let pos = 0;
  marks.forEach(([s, e]) => {
    if (s < pos) return;   // 겹치면 앞의 것만
    if (s > pos) nodes.push(document.createTextNode(source.slice(pos, s)));
    const mark = document.createElement('mark');
    mark.textContent = source.slice(s, e);
    nodes.push(mark);
    pos = e;
  });
  if (pos < source.length) nodes.push(document.createTextNode(source.slice(pos)));
  return nodes;
}
