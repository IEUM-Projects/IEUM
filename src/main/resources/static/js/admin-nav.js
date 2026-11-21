// 관리자 메뉴 (UI-04): 좁은 화면에서는 메뉴가 한 줄로 옆으로 밀리므로, 지금 화면 메뉴가 보이게 가운데로 옮겨 둔다
const current = document.querySelector('.admin-nav [aria-current="page"]');
const nav = document.querySelector('.admin-nav');
if (current && nav && nav.scrollWidth > nav.clientWidth) {
  nav.scrollLeft = current.offsetLeft - (nav.clientWidth - current.offsetWidth) / 2;
}
