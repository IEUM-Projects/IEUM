// 축제 날짜 표시 도우미 (달력 화면, 상세 화면이 함께 씀)
// 날짜는 'YYYY-MM-DD' 글자로 다룬다 (Date 시간대 때문에 하루가 밀리는 문제를 피하려고)

export const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

/** 관광공사 사진의 공공누리 유형 표기 (사진 아래 출처와 함께) */
export const LICENSES = {
  Type1: '공공누리 제1유형 (출처표시)',
  Type2: '공공누리 제2유형 (출처표시, 상업적 이용금지)',
  Type3: '공공누리 제3유형 (출처표시, 변경금지)',
  Type4: '공공누리 제4유형 (출처표시, 상업적 이용금지, 변경금지)',
};

export function photoCredit(license) {
  return `사진: 한국관광공사${LICENSES[license] ? ` · ${LICENSES[license]}` : ''}`;
}

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
