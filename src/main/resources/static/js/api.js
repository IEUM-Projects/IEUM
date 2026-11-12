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
    throw new Error(message);
  }
  return res.json();
}

export const api = {
  /** 반경 검색. categories: ['음식점', '카페·간식'] 처럼 배열, 비어 있으면 전체 */
  nearbyStores({ lat, lng, radius, categories = [] }) {
    const params = new URLSearchParams({ lat, lng, radius });
    categories.forEach((c) => params.append('category', c));
    return request(`/api/stores?${params}`);
  },
  storeDetail(id) {
    return request(`/api/stores/${encodeURIComponent(id)}`);
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
  festival(id) {
    return request(`/api/festivals/${encodeURIComponent(id)}`);
  },
};

/** 카카오 로그인에서 돌아왔을 때 주소에 붙는 실패 이유별 안내 문구 */
export const LOGIN_FAILURE_MESSAGES = {
  CANCELLED: '카카오 로그인을 취소했어요.',
  ACCOUNT_WITHDRAWN: '탈퇴한 계정이에요.',
  ERROR: '카카오 로그인 중 문제가 생겼어요. 잠시 후 다시 시도해 주세요.',
};

/** 카카오맵 SDK를 키를 받아 와서 한 번만 불러온다 */
let sdkPromise = null;
export function loadKakaoMapSdk() {
  if (sdkPromise) return sdkPromise;
  sdkPromise = api.mapConfig().then(({ javascriptKey }) => new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(javascriptKey)}&autoload=false`;
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

/** 카카오맵 길찾기 링크 (목적지 이름, 위도, 경도) */
export function directionsUrl(store) {
  return `https://map.kakao.com/link/to/${encodeURIComponent(store.name)},${store.latitude},${store.longitude}`;
}
