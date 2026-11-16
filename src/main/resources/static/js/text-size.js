// 글자 크기 조절 (UI-03): 보통 / 크게 / 아주 크게
// 글자 크기가 모두 rem 단위(tokens.css)라서 <html>의 글자 크기만 바꾸면 화면 전체가 함께 커진다.
// 고른 크기는 이 브라우저에만 기억한다 (편의 기능). header.js가 불러오므로 계정 영역이 있는 모든 화면에 적용된다.
const KEY = 'ieum_text_size';
export const TEXT_SIZES = [
  { scale: 1, label: '보통' },
  { scale: 1.125, label: '크게' },
  { scale: 1.25, label: '아주 크게' },
];

function saved() {
  try {
    const i = Number(localStorage.getItem(KEY));
    return Number.isInteger(i) && i >= 0 && i < TEXT_SIZES.length ? i : 0;
  } catch (_) {
    return 0;
  }
}

let current = saved();

function apply() {
  document.documentElement.style.fontSize = current === 0 ? '' : `${TEXT_SIZES[current].scale * 100}%`;
  document.documentElement.dataset.textSize = String(current);
}

export function textSize() {
  return current;
}

/** step: +1 크게, -1 작게. 바뀐 단계의 이름을 돌려준다 */
export function changeTextSize(step) {
  current = Math.min(TEXT_SIZES.length - 1, Math.max(0, current + step));
  try { localStorage.setItem(KEY, String(current)); } catch (_) { /* 저장이 안 되면 이번 화면에만 */ }
  apply();
  return TEXT_SIZES[current].label;
}

apply();
