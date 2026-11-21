// 홈 화면 앱처럼 쓰기 (PWA-01)
// - 서비스 워커 등록: 인터넷이 끊기면 '연결 없음' 화면 (sw.js)
// - 설치 안내: 크롬·삼성 인터넷은 '홈 화면에 추가' 버튼, 아이폰은 공유 메뉴 안내 (내 정보 화면이 씀)
if ('serviceWorker' in navigator && window.isSecureContext) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => { /* 못 해도 화면은 그대로 쓴다 */ });
  });
}

let installEvent = null;
window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();   // 브라우저가 멋대로 띄우지 않게 하고, 내 정보의 버튼으로만 띄운다
  installEvent = e;
  window.dispatchEvent(new Event('ieum-installable'));
});
window.addEventListener('appinstalled', () => {
  installEvent = null;
  window.dispatchEvent(new Event('ieum-installed'));
});

/** 이미 홈 화면 앱으로 열려 있는지 */
export function isStandalone() {
  return window.matchMedia?.('(display-mode: standalone)').matches || navigator.standalone === true;
}

/** 아이폰·아이패드 사파리 (설치 버튼이 없어 공유 메뉴로 안내) */
export function isIos() {
  return /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
}

export function canPromptInstall() {
  return installEvent !== null;
}

/** 설치 창을 띄운다. 설치하면 true */
export async function promptInstall() {
  if (!installEvent) return false;
  const e = installEvent;
  installEvent = null;
  e.prompt();
  const choice = await e.userChoice.catch(() => null);
  return choice?.outcome === 'accepted';
}
