// 축제 날짜 표시 도우미 (달력 화면, 상세 화면이 함께 씀)
// 날짜는 'YYYY-MM-DD' 글자로 다룬다 (Date 시간대 때문에 하루가 밀리는 문제를 피하려고)

export const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

export const pad = (n) => String(n).padStart(2, '0');
export const ymd = (y, m, d) => `${y}-${pad(m)}-${pad(d)}`;
export const daysIn = (y, m) => new Date(Date.UTC(y, m, 0)).getUTCDate();
export const weekday = (s) => new Date(`${s}T00:00:00Z`).getUTCDay();   // 0=일

export function todayString() {
  const now = new Date();
  return ymd(now.getFullYear(), now.getMonth() + 1, now.getDate());
}

/** '2026-11-13' → '11.13(금)' */
export function shortDate(s) {
  const [, m, d] = s.split('-').map(Number);
  return `${m}.${d}(${WEEKDAYS[weekday(s)]})`;
}

/** '2026-11-13' → '2026년 11월 13일(금)' */
export function longDate(s) {
  const [y, m, d] = s.split('-').map(Number);
  return `${y}년 ${m}월 ${d}일(${WEEKDAYS[weekday(s)]})`;
}

export function periodText(f) {
  return f.startDate === f.endDate ? shortDate(f.startDate) : `${shortDate(f.startDate)} ~ ${shortDate(f.endDate)}`;
}

/** 진행 상태: 끝남 / 진행 중 / 예정 */
export function status(f, today = todayString()) {
  if (f.endDate < today) return { text: '끝남', cls: 'status-ended' };
  if (f.startDate <= today) return { text: '진행 중', cls: 'status-now' };
  return { text: '예정', cls: 'status-soon' };
}
