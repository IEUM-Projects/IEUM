// 찜하기 버튼 (FAV-01): 가게·시장 상세 화면 제목 아래.
// - 로그인 전이면 로그인 화면에 갔다가 돌아와서(#save) 바로 찜한다
// - 누를 때마다 찜하기 ↔ 찜 풀기. 결과는 아래 한 줄로 알려 준다 (화면 낭독기도 읽음)
import * as auth from './auth.js';

const HEART = '<path d="M12 20.3s-7.3-4.4-8.8-9.1C2.1 7.8 4.3 5 7.4 5c2 0 3.6 1.1 4.6 2.6C13 6.1 14.6 5 16.6 5c3.1 0 5.3 2.8 4.2 6.2-1.5 4.7-8.8 9.1-8.8 9.1z"/>';

/**
 * @param {{type: 'store'|'market', id: number|string, name: string}} target
 * @returns {HTMLElement} 버튼 + 안내 한 줄
 */
export function favoriteButton({ type, id, name }) {
  const box = document.createElement('div');
  box.className = 'favorite';
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'favorite-button';
  button.setAttribute('aria-pressed', 'false');
  const status = document.createElement('p');
  status.className = 'favorite-status';
  status.setAttribute('role', 'status');
  box.append(button, status);

  const url = `/api/favorites/${type}/${encodeURIComponent(id)}`;
  let saved = false;
  let busy = false;

  function paint() {
    button.setAttribute('aria-pressed', String(saved));
    button.classList.toggle('saved', saved);
    button.innerHTML = `<svg class="icon-small" viewBox="0 0 24 24" fill="${saved ? 'currentColor' : 'none'}" stroke="currentColor" stroke-width="1.6" aria-hidden="true">${HEART}</svg>`;
    button.append(saved ? '찜함' : '찜하기');
    button.setAttribute('aria-label', saved ? `${name} 찜함 (누르면 찜을 풀어요)` : `${name} 찜하기`);
  }

  function goLogin() {
    location.href = auth.loginUrl(`${location.pathname}${location.search}#save`);
  }

  async function set(next) {
    if (busy) return;
    busy = true;
    button.disabled = true;
    try {
      const res = await auth.authFetch(url, { method: next ? 'PUT' : 'DELETE' });
      if (res.status === 401) { goLogin(); return; }
      const body = await res.json().catch(() => ({}));
      if (!res.ok) { status.textContent = body.message ?? '잠시 후 다시 시도해 주세요.'; status.classList.add('error'); return; }
      saved = !!body.saved;
      paint();
      status.classList.remove('error');
      status.textContent = saved ? '찜했어요. 내 정보 › 찜한 곳에서 모아 볼 수 있어요.' : '찜을 풀었어요.';
    } catch (_) {
      status.textContent = '잠시 후 다시 시도해 주세요.';
      status.classList.add('error');
    } finally {
      busy = false;
      button.disabled = false;
    }
  }

  button.addEventListener('click', () => {
    if (!auth.getUser()) { goLogin(); return; }
    set(!saved);
  });

  paint();
  // 로그인한 사람이면 찜했는지 불러오고, 로그인하고 돌아온 경우(#save)면 바로 찜한다
  auth.init().then(async (user) => {
    if (!user) return;
    if (location.hash === '#save') {
      history.replaceState(null, '', location.pathname + location.search);
      await set(true);
      return;
    }
    try {
      const res = await auth.authFetch(url);
      if (res.ok) { saved = !!(await res.json()).saved; paint(); }
    } catch (_) { /* 못 불러오면 '찜하기'로 둔다 */ }
  });
  return box;
}
