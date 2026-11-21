// 관리자 작업 기록 한 줄 (ADM-03): 요약 화면과 작업 기록 화면이 같이 쓴다
import { el } from './community-common.js';

const TYPE_LABELS = { STORE: '가게', POST: '글', COMMENT: '댓글', USER: '회원', MARKET: '시장', NOTICE: '공지', FACILITY: '시장 시설' };

export function when(iso) {
  return iso ? `${iso.slice(0, 10).replaceAll('-', '.')} ${iso.slice(11, 16)}` : '';
}

function target(t) {
  if (!t) return null;
  const name = t.name ?? `#${t.id}`;
  const text = `${TYPE_LABELS[t.type] ?? t.type} · ${name}`;
  return t.url ? el('a', { href: t.url, text }) : el('span', { text: t.name ? text : `${text} (지금은 없음)` });
}

export function actionRow(a) {
  return el('li', { class: 'action-row', 'data-id': String(a.id), 'data-action': a.action },
    el('p', { class: 'action-head' },
      el('span', { class: 'chip-count', text: a.categoryLabel }),
      el('strong', { class: 'action-label', text: a.label }),
      target(a.target)),
    a.summary ? el('p', { class: 'action-summary', text: a.summary }) : null,
    el('p', { class: 'card-meta' }, el('time', { datetime: a.at, text: when(a.at) }), ` · ${a.admin}`));
}
