// 가게 상세 화면 (MAP-02): /store.html?id=381
import { api, directionsUrl } from './api.js';

const root = document.getElementById('detail');

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

function row(label, value) {
  return el('div', {}, el('dt', { text: label }), el('dd', { text: value || '정보 없음' }));
}

function render(store) {
  document.title = `${store.name} - 이음`;
  const isCreator = store.type === 'CREATOR';
  const unverified = store.infoStatus === 'UNVERIFIED';

  // 제보 기능은 로그인이 필요해서 회원 기능(Phase 2) 이후에 연결한다
  const reportButton = el('button', { type: 'button', class: 'button-secondary wide', disabled: '', text: '영업 정보 제보 (로그인 기능 준비 중)' });

  // 조건에 따라 null이 되는 항목은 빼고 넣는다 (그대로 넣으면 화면에 "null" 글자가 찍힘)
  root.replaceChildren(...[
    el('span', { class: isCreator ? 'badge-type creator' : 'badge-type', text: isCreator ? '로컬크리에이터' : '백년가게' }),
    el('h1', { text: store.name }),
    el('p', { class: 'category', text: store.category ?? '분류 없음' }),
    unverified
      ? el('div', { class: 'warning' },
          el('strong', { text: '영업 정보 미확인' }),
          '최근 영업 여부를 확인하지 못한 가게예요. 방문 전 전화나 지도 앱으로 확인해 주세요.')
      : null,
    el('dl', { class: 'info' },
      row('주소', store.roadAddress ?? store.jibunAddress),
      store.roadAddress && store.jibunAddress ? row('지번', store.jibunAddress) : null,
      row('전화', store.phone),
      row('영업시간', store.hours),
      store.certifiedYear ? row('지정 연도', `${store.certifiedYear}년`) : null,
    ),
    store.history ? el('section', {}, el('h2', { text: '가게 이야기' }), el('p', { text: store.history })) : null,
    el('div', { class: 'actions' },
      el('a', { class: 'button-primary', href: directionsUrl(store), target: '_blank', rel: 'noopener', text: '길찾기' }),
      store.phone
        ? el('a', { class: 'button-secondary', href: `tel:${store.phone}`, text: '전화하기' })
        : el('button', { type: 'button', class: 'button-secondary', disabled: '', text: '전화번호 없음' }),
      store.kakaoMapUrl
        ? el('a', { class: 'button-secondary wide', href: store.kakaoMapUrl, target: '_blank', rel: 'noopener', text: '카카오맵에서 보기 (사진·리뷰)' })
        : null,
      reportButton,
    ),
  ].filter(Boolean));
}

async function init() {
  const id = new URLSearchParams(location.search).get('id');
  if (!id || !/^\d+$/.test(id)) {
    root.replaceChildren(el('p', { class: 'error', text: '가게 주소가 올바르지 않아요.' }));
    return;
  }
  try {
    render(await api.storeDetail(id));
  } catch (e) {
    root.replaceChildren(el('p', { class: 'error', text: '가게 정보를 불러오지 못했어요. 지도로 돌아가 다시 선택해 주세요.' }));
  }
}

init();
