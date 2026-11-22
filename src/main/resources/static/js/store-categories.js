// 백년가게 분류 (CAT-01): 홈 탭·홈 메뉴·지도 칩이 같은 목록·같은 순서를 쓴다.
// 화면에는 '받아 온 가게에 실제로 있는 분류'만 보여 준다 (빈 탭이 생기지 않게).
// 분류가 없거나 이 목록에 없는 분류는 '기타'로 묶는다.
export const STORE_CATEGORIES = ['음식점', '카페·간식', '도소매', '생활서비스', '공방·공예'];
export const OTHER = '기타';

/** 이 가게의 화면용 분류 */
export const categoryOf = (store) => (STORE_CATEGORIES.includes(store.category) ? store.category : OTHER);

/** 주소 등에서 받은 분류 값이 쓸 수 있는 값인지 */
export const isStoreCategory = (c) => STORE_CATEGORIES.includes(c) || c === OTHER;

/** 가게 목록에 실제로 있는 분류 (정해진 순서, '기타'는 맨 끝) */
export function categoriesIn(stores) {
  const have = new Set(stores.map(categoryOf));
  return [...STORE_CATEGORIES, OTHER].filter((c) => have.has(c));
}

/** 고른 분류들(비면 전체)에 드는 가게만 */
export function inCategories(stores, chosen) {
  if (!chosen || chosen.size === 0) return stores;
  return stores.filter((s) => chosen.has(categoryOf(s)));
}
