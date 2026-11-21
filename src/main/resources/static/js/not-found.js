// 상세 화면(가게·시장·축제)에서 그 항목이 없거나 불러오지 못했을 때 보여 줄 덩어리 (ERR-01)
// 없을 때(404·잘못된 주소)와 잠시 못 불러온 때를 나눠서 말하고, 할 수 있는 일을 버튼으로 준다.

/**
 * @param {object} o
 * @param {boolean} o.notFound   true: 없는 항목, false: 불러오기 실패(다시 시도)
 * @param {string} o.title       없을 때 제목 (예: '가게를 찾을 수 없어요')
 * @param {string} o.reason      없을 때 설명 (예: '문을 닫았거나 정보가 내려갔을 수 있어요.')
 * @param {string} o.failTitle   불러오기 실패 제목 (예: '가게 정보를 불러오지 못했어요')
 * @param {{href: string, label: string}} o.back  돌아갈 곳 (예: 지도)
 */
export function notFoundBlock({ notFound, title, reason, failTitle, back }) {
  const box = document.createElement('section');
  box.className = 'not-found-block';
  box.setAttribute('role', 'alert');
  const h = document.createElement('h1');
  h.className = 'not-found-title';
  h.textContent = notFound ? title : failTitle;
  const p = document.createElement('p');
  p.className = 'not-found-lead';
  p.textContent = notFound
    ? `${reason} 주소를 다시 확인하거나 아래에서 찾아보세요.`
    : '잠시 연결이 원활하지 않아요. 잠시 뒤에 다시 시도해 주세요.';
  const actions = document.createElement('div');
  actions.className = 'not-found-actions';
  const link = (href, label, primary) => {
    const a = document.createElement('a');
    a.href = href;
    a.className = primary ? 'button-primary' : 'button-secondary';
    a.textContent = label;
    return a;
  };
  if (notFound) {
    actions.append(link(back.href, back.label, true), link('/', '홈으로', false));
    document.title = `${title} - 이음`;
    // 없는 항목 주소가 검색 결과에 남지 않게
    const robots = document.createElement('meta');
    robots.name = 'robots';
    robots.content = 'noindex';
    document.head.append(robots);
  } else {
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'button-primary';
    retry.textContent = '다시 시도';
    retry.addEventListener('click', () => location.reload());
    actions.append(retry, link(back.href, back.label, false));
  }
  box.append(h, p, actions);
  return box;
}
