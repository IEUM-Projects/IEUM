// 상단 바의 계정 영역 (지도 화면, 가게 상세 화면이 함께 씀)
// 로그인 전: "로그인" 링크 / 로그인 후: 알림 종(안 읽은 수) + 닉네임 버튼 → 메뉴(내 정보, 알림, 관리자일 때 관리자, 로그아웃)
import * as auth from './auth.js';
import './text-size.js';   // 고른 글자 크기를 이 화면에도 적용

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([key, value]) => {
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value);
  });
  children.flat().filter(Boolean).forEach((child) => node.append(child));
  return node;
}

// ---------------------------------------------------------------- 알림 종 (NOTI-01)
const BELL = '<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">'
  + '<path d="M6 16V11a6 6 0 1 1 12 0v5l1.5 2h-15L6 16z"/><path d="M10 20.5a2.2 2.2 0 0 0 4 0"/></svg>';
let unreadAt = 0;
let unreadPromise = null;

/** 안 읽은 알림 수 (1분 안에 다시 물으면 앞서 받은 값) */
export function unreadCount(force = false) {
  if (!force && unreadPromise && Date.now() - unreadAt < 60000) return unreadPromise;
  unreadAt = Date.now();
  unreadPromise = auth.authFetch('/api/notifications/unread-count')
    .then((res) => (res.ok ? res.json() : { unread: 0 })).then((b) => b.unread ?? 0).catch(() => 0);
  return unreadPromise;
}

export function paintBell(bell, n) {
  const badge = bell.querySelector('.noti-badge');
  badge.textContent = n > 99 ? '99+' : String(n);
  badge.hidden = n === 0;
  bell.setAttribute('aria-label', n ? `알림, 안 읽은 알림 ${n}개` : '알림');
  // 종을 감추는 좁은 지도 화면에서도 알 수 있게: 닉네임 버튼에 점, 메뉴에 수
  const box = bell.closest('.account');
  box?.querySelector('.account-button')?.classList.toggle('has-unread', n > 0);
  const item = box?.querySelector('.menu-noti');
  if (item) item.textContent = n ? `알림 ${n > 99 ? '99+' : n}` : '알림';
}

/**
 * box: 계정 영역 요소, onLogout: 로그아웃 뒤 할 일(안내 문구 등)
 * next: 로그인 후 돌아올 주소 (기본값: 지금 화면)
 * 로그인 상태가 바뀔 때마다 자동으로 다시 그린다.
 */
export function mountAccount(box, { onLogout, next } = {}) {
  let closeMenu = () => {};

  function render(user) {
    closeMenu = () => {};
    if (!user) {
      // 로그인 후 지금 보던 화면으로 돌아오도록 현재 주소를 넘긴다
      box.replaceChildren(el('a', { class: 'account-link', href: auth.loginUrl(next ?? location.pathname + location.search), text: '로그인' }));
      return;
    }
    const button = el('button', {
      type: 'button', class: 'account-button', 'aria-haspopup': 'true', 'aria-expanded': 'false',
      'aria-controls': 'accountMenu', text: user.nickname,
    });
    const logout = el('button', { type: 'button', role: 'menuitem', text: '로그아웃' });
    const bell = el('a', { class: 'noti-link', href: '/notifications.html', 'aria-label': '알림' });
    bell.innerHTML = BELL;
    bell.append(el('span', { class: 'noti-badge', 'aria-hidden': 'true', hidden: '' }));
    const refresh = (force) => unreadCount(force).then((n) => paintBell(bell, n));
    refresh(false);
    // 다른 탭에서 돌아오면 다시 센다 (1분에 한 번까지), 알림 화면에서 읽으면 바로
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') refresh(false); });
    window.addEventListener('ieum-notifications', () => refresh(true));
    const menu = el('div', { id: 'accountMenu', class: 'account-menu', role: 'menu', hidden: '' },
      el('div', { class: 'menu-name', text: `${user.nickname}님` }),
      el('a', { href: '/mypage.html', role: 'menuitem', text: '내 정보' }),
      el('a', { href: '/notifications.html', role: 'menuitem', class: 'menu-noti', text: '알림' }),
      user.role === 'ADMIN' ? el('a', { href: '/admin-home.html', role: 'menuitem', text: '관리자' }) : null,
      logout,
    );
    const setOpen = (open) => {
      menu.hidden = !open;
      button.setAttribute('aria-expanded', String(open));
    };
    closeMenu = () => setOpen(false);
    button.addEventListener('click', (event) => {
      event.stopPropagation();
      setOpen(menu.hidden);
      if (!menu.hidden) menu.querySelector('[role="menuitem"]')?.focus();
    });
    // Esc로 닫고 닉네임 버튼으로 초점을 돌려준다 (키보드 사용자)
    menu.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') { setOpen(false); button.focus(); }
    });
    logout.addEventListener('click', async () => {
      await auth.logout();
      onLogout?.();
    });
    box.replaceChildren(bell, button, menu);
  }

  document.addEventListener('click', (event) => {
    if (!box.contains(event.target)) closeMenu();
  });
  auth.onAuthChange(render);
  render(auth.getUser());
}
