// 전통시장 시설 정보 (DATA-01): 시장 화면과 지도에서 같이 쓴다
// 값: true 있음 / false 없음 / null 정보 없음 (정보 없음은 '없음'으로 보여 주지 않는다)

/** 시장 화면의 묶음. key는 서버 응답(facilities)의 이름, 주차장·화장실은 시장 기본 정보에서 */
export const FACILITY_GROUPS = [
  { title: '편하게 장 보기', items: [
    ['hasParking', '주차장'], ['hasToilet', '공중화장실'], ['arcade', '아케이드 (비 와도 괜찮아요)'],
    ['elevator', '엘리베이터·에스컬레이터'], ['shoppingCart', '쇼핑카트'], ['lockers', '물품보관함'], ['bikeRack', '자전거보관함'],
  ] },
  { title: '아이와 함께', items: [['nursingRoom', '수유센터'], ['kidsRoom', '유아놀이방'], ['restArea', '고객휴게실']] },
  { title: '도움받기', items: [['helpCenter', '고객지원센터'], ['foreignDesk', '외국인 안내센터'], ['aed', '자동심장충격기']] },
];

/** 지도에서 시장을 거르는 조건 (?need=parking&need=arcade) */
export const MARKET_NEEDS = {
  parking: { label: '주차 가능', test: (m) => m.hasParking === true },
  arcade: { label: '비 와도 OK', test: (m) => m.arcade === true },
  kids: { label: '아이와 함께', test: (m) => m.kidsFriendly === true },
};

/** 시장 정보 + 시설 정보에서 묶음별로 '있음'인 것만 */
export function facilityGroups(market) {
  const value = (key) => (key === 'hasParking' || key === 'hasToilet' ? market[key] : market.facilities?.[key]);
  return FACILITY_GROUPS.map((g) => ({ title: g.title, items: g.items.filter(([key]) => value(key) === true).map(([, label]) => label) }));
}
