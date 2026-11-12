// 전통시장 장날 계산 (MKT-01)
// 서버는 장 서는 날의 끝자리만 준다: "1,6" → 1·6·11·16·21·26일, "5,0" → 5·10·15·20·25·30일 (0은 10·20·30일)
// 31일은 시장마다 달라서 장날로 계산하지 않는다.
import { WEEKDAYS, pad, weekday, todayString } from './festival-format.js';

/** "1,6" → [1, 6]. 정기장이 없으면 [] */
export function digitsOf(marketDays) {
  return (marketDays ?? '').split(',').filter((d) => /^\d$/.test(d)).map(Number);
}

/** [1, 6] → "1·6일" ([5, 0] → "5·10일") */
export function cycleLabel(digits) {
  return `${digits.map((d) => (d === 0 ? 10 : d)).join('·')}일`;
}

/** 한 달 중 장 서는 날 [1, 6, 11, 16, 21, 26] (31일 제외) */
export function daysOfMonth(digits) {
  const days = [];
  for (let d = 1; d <= 30; d++) if (digits.includes(d % 10)) days.push(d);
  return days;
}

export function isMarketDay(digits, date = todayString()) {
  const day = Number(date.slice(8, 10));
  return day <= 30 && digits.includes(day % 10);
}

/** 오늘 이후(오늘 포함) 첫 장날 'YYYY-MM-DD'. 없으면 null */
export function nextMarketDay(digits, from = todayString()) {
  if (!digits.length) return null;
  const [y, m, d] = from.split('-').map(Number);
  for (let i = 0; i < 45; i++) {
    const t = new Date(Date.UTC(y, m - 1, d + i));
    const s = `${t.getUTCFullYear()}-${pad(t.getUTCMonth() + 1)}-${pad(t.getUTCDate())}`;
    if (isMarketDay(digits, s)) return s;
  }
  return null;
}

/** '2026-11-16' → '11월 16일(월)' */
export function dayText(s) {
  return `${Number(s.slice(5, 7))}월 ${Number(s.slice(8, 10))}일(${WEEKDAYS[weekday(s)]})`;
}

/**
 * 카드·목록에 쓰는 한 줄 요약
 * - 상설장: "매일 열려요"
 * - 정기장: "1·6일장 · 오늘 장 서요" / "1·6일장 · 다음 장날 11월 16일(월)"
 * - 상설장+정기장: "매일 열고, 1·6일에 장이 서요 · ..."
 */
export function marketDaySummary(market, today = todayString()) {
  const digits = digitsOf(market.marketDays);
  if (!digits.length) return market.permanent ? '매일 열려요' : '장날 정보 없음';
  const head = market.permanent ? `매일 열고, ${cycleLabel(digits)}에 장이 서요` : `${cycleLabel(digits)}장`;
  if (isMarketDay(digits, today)) return `${head} · 오늘 장 서요`;
  const next = nextMarketDay(digits, today);
  return next ? `${head} · 다음 장날 ${dayText(next)}` : head;
}
