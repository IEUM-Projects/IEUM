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

/** 카카오맵 길찾기 링크 (목적지 이름, 위도, 경도). 좌표가 없으면 카카오맵 장소 ID나 이름 검색으로 */
export function directionsUrl(store) {
  if (store.latitude == null || store.longitude == null) {
    return store.kakaoPlaceId
      ? `https://map.kakao.com/link/to/${encodeURIComponent(store.kakaoPlaceId)}`
      : `https://map.kakao.com/link/search/${encodeURIComponent(store.name)}`;
  }
  return `https://map.kakao.com/link/to/${encodeURIComponent(store.name)},${store.latitude},${store.longitude}`;
}
