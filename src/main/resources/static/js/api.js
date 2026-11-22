// 서버 API 호출을 한곳에 모은 모듈.
// 화면 코드는 fetch 주소를 직접 쓰지 않고 이 함수들만 부른다. (주소가 바뀌어도 여기만 고치면 됨)

async function request(url) {
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!res.ok) {
    let message = `요청 실패 (${res.status})`;
    try {
      const body = await res.json();
      if (body && (body.message || body.detail)) message = body.message || body.detail;
    } catch (_) { /* 본문이 JSON이 아니면 기본 메시지 사용 */ }
    throw Object.assign(new Error(message), { status: res.status });   // status: 404 면 '없는 항목' (ERR-01)
  }
  return res.json();
}

export const api = {
  /** 반경 검색. categories: ['음식점', '카페·간식'] 처럼 배열, 비어 있으면 전체 */
  nearbyStores({ lat, lng, radius, categories = [], type = '' }) {
    const params = new URLSearchParams({ lat, lng, radius });
    categories.forEach((c) => params.append('category', c));
    if (type) params.set('type', type);   // CENTURY(백년가게) / CREATOR(로컬크리에이터), 없으면 둘 다
    return request(`/api/stores?${params}`);
  },
  storeDetail(id) {
    return request(`/api/stores/${encodeURIComponent(id)}`);
  },
  /** 이용약관·개인정보 처리방침의 판, 시행일, 운영자 정보 (POL-01) */
  policy() {
    return request('/api/policy');
  },
  regions() {
    return request('/api/regions');
  },
  mapConfig() {
    return request('/api/config/map');
  },
  /** 반경 안의 전통시장 (가까운 순) */
  nearbyMarkets({ lat, lng, radius }) {
    return request(`/api/markets?${new URLSearchParams({ lat, lng, radius })}`);
  },
  market(id) {
    return request(`/api/markets/${encodeURIComponent(id)}`);
  },
  /** 기간(from~to, 'YYYY-MM-DD')과 겹치는 축제. sido: '11' 같은 시·도 코드, 비우면 전국 */
  festivals({ from, to, sido = '' }) {
    const params = new URLSearchParams({ from, to });
    if (sido) params.set('sido', sido);
    return request(`/api/festivals?${params}`);
  },
  /** 내 주변 축제 (홈 화면): 기간과 겹치고 반경(m) 안에 있는 축제 */
  festivalsNearby({ lat, lng, from, to, radius = 30000 }) {
    return request(`/api/festivals/nearby?${new URLSearchParams({ lat, lng, from, to, radius })}`);
  },
  festival(id) {
    return request(`/api/festivals/${encodeURIComponent(id)}`);
  },
};

/** 카카오 로그인에서 돌아왔을 때 주소에 붙는 실패 이유별 안내 문구 */
export const LOGIN_FAILURE_MESSAGES = {
  CANCELLED: '카카오 로그인을 취소했어요.',
  ACCOUNT_WITHDRAWN: '탈퇴한 계정이에요.',
  ACCOUNT_BANNED: '이용약관 위반으로 탈퇴 처리된 계정이라 다시 가입할 수 없어요.',
  ERROR: '카카오 로그인 중 문제가 생겼어요. 잠시 후 다시 시도해 주세요.',
};

/** 카카오맵 SDK를 키를 받아 와서 한 번만 불러온다 */
let sdkPromise = null;
export function loadKakaoMapSdk() {
  if (sdkPromise) return sdkPromise;
  sdkPromise = api.mapConfig().then(({ javascriptKey }) => new Promise((resolve, reject) => {
    const script = document.createElement('script');
    // services: 가게 화면에서 카카오맵 전화번호를 그때그때 불러올 때 (DATA-05, 저장하지 않음)
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(javascriptKey)}&autoload=false&libraries=services`;
    script.onload = () => window.kakao.maps.load(() => resolve(window.kakao));
    script.onerror = () => reject(new Error('카카오맵을 불러오지 못했어요. JavaScript 키와 등록 도메인을 확인하세요.'));
    document.head.appendChild(script);
  }));
  return sdkPromise;
}

/** 거리 표시: 950 → "950m", 1530 → "1.5km" */
export function formatDistance(m) {
  return m < 1000 ? `${m}m` : `${(m / 1000).toFixed(1)}km`;
}

// ---------------------------------------------------------------- 길찾기 (ROUTE-01)
// 웹 카카오맵 링크(/link/to)는 목적지만 채운다 (출발지는 카카오맵에서 입력).
// 휴대폰에서는 카카오맵 '앱'의 도보 길찾기로 연다: 출발지를 비워 두면 앱이 자기 위치 권한으로 현재 위치를 채운다.
// 이음은 내 위치를 받지도, 카카오에 넘기지도 않는다 (LOC-02 그대로). 앱이 없으면 웹 카카오맵으로.

const KAKAOMAP_ANDROID = 'net.daum.android.map';

/** 휴대폰·태블릿인지 (iPadOS 는 Mac 으로 보이므로 터치로 가린다) */
export function isMobileDevice() {
  const ua = navigator.userAgent || '';
  return /Android|iPhone|iPad|iPod/i.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
}

/** 앱 길찾기 목적지 'lat,lng' (좌표가 없으면 null) */
function routeEp(place) {
  const lat = Number(place.latitude);
  const lng = Number(place.longitude);
  if (place.latitude == null || place.longitude == null || !Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  return `${lat.toFixed(6)},${lng.toFixed(6)}`;
}

/**
 * 길찾기 링크에 붙일 속성. href 는 웹 카카오맵(PC, 앱 없을 때, 길게 눌러 복사할 때),
 * data-route-ep 가 있으면 휴대폰에서 누를 때 카카오맵 앱으로 연다 (아래 click 처리).
 * el('a', { class: 'button-primary', ...routeAttrs(store) }, '길찾기')
 */
export function routeAttrs(place) {
  const ep = routeEp(place);
  return { href: directionsUrl(place), target: '_blank', rel: 'noopener', ...(ep ? { 'data-route-ep': ep } : {}) };
}

/** 길찾기 버튼 아래 안내 한 줄 */
export function routeNote() {
  return isMobileDevice()
    ? '카카오맵 앱이 있으면 앱에서 내 위치로 길을 찾아요. 앱이 없으면 카카오맵 웹에서 출발지를 정해 주세요.'
    : '카카오맵이 새 창으로 열려요. 출발지를 입력하면 길을 찾아요.';
}

/** 주소 이동 (시험에서는 window.__ieumGo 로 가로채 기록만 한다) */
function go(url) {
  if (typeof window.__ieumGo === 'function') window.__ieumGo(url);
  else location.href = url;
}

function openKakaoMapApp(ep, webUrl) {
  const query = `ep=${ep}&by=FOOT`;
  if (/Android/i.test(navigator.userAgent)) {
    // 안드로이드: 앱이 없으면 크롬이 알아서 웹 주소로 (타이머 없이)
    go(`intent://route?${query}#Intent;scheme=kakaomap;package=${KAKAOMAP_ANDROID};S.browser_fallback_url=${encodeURIComponent(webUrl)};end`);
    return;
  }
  // 아이폰·아이패드: 앱 주소로 가 보고, 1.5초 뒤에도 이 화면이 그대로면(앱이 안 열림) 웹 카카오맵으로
  let left = false;
  const leave = () => { left = true; };
  document.addEventListener('visibilitychange', leave, { once: true });
  window.addEventListener('pagehide', leave, { once: true });
  // blur 는 쓰지 않는다: 앱이 없을 때 사파리가 띄우는 '열 수 없음' 알림에도 blur 가 나서 웹으로 못 넘어갈 수 있음
  go(`kakaomap://route?${query}`);
  setTimeout(() => {
    if (!left && document.visibilityState === 'visible') go(webUrl);
  }, 1500);
}

// 어느 화면이든 data-route-ep 가 붙은 링크를 휴대폰에서 누르면 앱으로 (화면마다 따로 달지 않게 한 번만)
if (typeof document !== 'undefined' && !window.__ieumRouteClick) {
  window.__ieumRouteClick = true;
  document.addEventListener('click', (event) => {
    const a = event.target instanceof Element ? event.target.closest('a[data-route-ep]') : null;
    if (!a || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    if (!isMobileDevice()) return;   // PC 는 지금처럼 웹 카카오맵 새 창
    event.preventDefault();
    openKakaoMapApp(a.dataset.routeEp, a.href);
  });
}

/** 카카오맵 길찾기 링크 (목적지 이름, 위도, 경도). 좌표가 없으면 카카오맵 장소 ID나 이름 검색으로 */
export function directionsUrl(store) {
  if (store.latitude == null || store.longitude == null) {
    return store.kakaoPlaceId
      ? `https://map.kakao.com/link/to/${encodeURIComponent(store.kakaoPlaceId)}`
      : `https://map.kakao.com/link/search/${encodeURIComponent(store.name)}`;
  }
  // 이름 속 쉼표는 카카오가 '이름,위도,경도' 구분으로 읽어 링크가 깨지므로 빈칸으로
  const name = String(store.name ?? '목적지').replace(/[,，]/g, ' ').replace(/\s+/g, ' ').trim() || '목적지';
  return `https://map.kakao.com/link/to/${encodeURIComponent(name)},${store.latitude},${store.longitude}`;
}
