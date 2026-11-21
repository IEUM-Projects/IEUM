// 이음 서비스 워커 (PWA-01): 인터넷이 끊겼을 때 '연결 없음' 화면만 보여 준다.
// 화면·데이터는 저장해 두지 않는다 (늘 서버에서 새로 받음). 그래서 옛 화면이 남아 헷갈리는 일이 없다.
// 이 파일을 고치면 VERSION 을 올린다: 브라우저가 새 워커를 받아 옛 저장분을 지운다.
const VERSION = 'ieum-offline-v1';
const OFFLINE_URL = '/offline.html';
const FILES = [OFFLINE_URL, '/css/tokens.css', '/css/common.css', '/css/error.css',
  '/images/favicon.svg', '/images/ieum-symbol-small.svg'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(VERSION).then((cache) => cache.addAll(FILES.map((u) => new Request(u, { cache: 'reload' })))).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  // 화면 이동: 서버에서 받고, 못 받으면(오프라인) 연결 없음 화면
  const path = new URL(req.url).pathname;
  // 카카오 로그인 주소는 브라우저에 그대로 맡긴다 (다른 사이트로 넘어갔다 돌아오는 흐름)
  if (path.startsWith('/oauth2/') || path.startsWith('/login/oauth2/')) return;
  if (req.mode === 'navigate') {
    event.respondWith(fetch(req).catch(() => caches.match(OFFLINE_URL)));
    return;
  }
  // 연결 없음 화면이 쓰는 파일만: 서버에서 받고, 못 받으면 저장분
  const url = new URL(req.url);
  if (url.origin === location.origin && FILES.includes(url.pathname)) {
    event.respondWith(fetch(req).catch(() => caches.match(url.pathname)));
  }
  // 그 밖(API·지도·이미지 등)은 손대지 않는다
});
