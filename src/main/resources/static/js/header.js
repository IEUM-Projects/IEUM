// 상단 바의 계정 영역 (지도 화면, 가게 상세 화면이 함께 씀)
// 로그인 전: "로그인" 링크 / 로그인 후: 닉네임 버튼 → 메뉴(내 정보, 관리자일 때 관리자, 로그아웃)
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
    const menu = el('div', { id: 'accountMenu', class: 'account-menu', role: 'menu', hidden: '' },
      el('div', { class: 'menu-name', text: `${user.nickname}님` }),
      el('a', { href: '/mypage.html', role: 'menuitem', text: '내 정보' }),
      user.role === 'ADMIN' ? el('a', { href: '/admin.html', role: 'menuitem', text: '관리자' }) : null,
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
    box.replaceChildren(button, menu);
  }

  document.addEventListener('click', (event) => {
    if (!box.contains(event.target)) closeMenu();
  });
  auth.onAuthChange(render);
  render(auth.getUser());
}
