// 가게 상세 화면 (MAP-02): /store.html?id=381
import { api, directionsUrl, formatDistance } from './api.js';
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { marketDaySummary } from './market-days.js';

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
  return el('div', {}, el('dt', { text: label }), el('dd', value ? { text: value } : { class: 'empty', text: '정보 없음' }));
}

// 아이콘 (장식이라 화면 읽기 프로그램은 건너뜀)
const ICONS = {
  warning: '<path d="M12 3l9.5 17h-19z"/><path d="M12 10v4.5M12 17.5v.5"/>',
  directions: '<path d="M3 11l18-8-8 18-2-8z"/>',
};
function icon(name, className = 'icon') {
  const span = document.createElement('span');
  span.innerHTML = `<svg class="${className}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">${ICONS[name]}</svg>`;
  return span.firstChild;
}

/** 온누리상품권 (DATA-04): '종이·디지털 사용 가능' 처럼 */
function onnuriText(o) {
  const kinds = [o.paper ? '종이' : null, o.digital ? '디지털(카드·모바일)' : null].filter(Boolean).join('·');
  return `온누리 ${kinds || '종이'} 상품권 가맹점`;
}

function render(store) {
  document.title = `${store.name} - 이음`;
  const isCreator = store.type === 'CREATOR';
  const unverified = store.infoStatus === 'UNVERIFIED';

  // 영업 정보 제보 (로그인 필요. 로그인 전이면 로그인 화면에 갔다가 이 화면으로 돌아와 창을 연다)
  const reportButton = el('button', { type: 'button', id: 'reportButton', class: 'button-secondary wide', 'aria-haspopup': 'dialog', text: '영업 정보 제보' });
  reportButton.addEventListener('click', () => openReport(store));

  // 조건에 따라 null이 되는 항목은 빼고 넣는다 (그대로 넣으면 화면에 "null" 글자가 찍힘)
  root.replaceChildren(...[
    el('div', { class: 'title-block' },
      el('div', { class: 'badges' },
        el('span', { class: isCreator ? 'badge-type creator' : 'badge-type', text: isCreator ? '로컬크리에이터' : '백년가게' }),
        el('span', { class: 'badge-category', text: store.category ?? '분류 없음' }),
        store.onnuri ? el('span', { class: 'badge-onnuri', text: '온누리상품권' }) : null,
      ),
      el('h1', { text: store.name }),
    ),
    unverified
      ? el('div', { class: 'warning', role: 'note' },
          icon('warning'),
          el('div', {},
            el('strong', { text: '영업 정보 미확인' }),
            '최근 영업 여부를 확인하지 못한 가게예요. 방문 전 전화나 지도 앱으로 확인해 주세요.'))
      : null,
    el('dl', { class: 'info' },
      row('주소', store.roadAddress ?? store.jibunAddress),
      store.roadAddress && store.jibunAddress ? row('지번', store.jibunAddress) : null,
      row('전화', store.phone),
      row('영업시간', store.hours),
      store.certifiedYear ? row('지정 연도', `${store.certifiedYear}년`) : null,
      store.onnuri ? row('상품권', onnuriText(store.onnuri)) : null,
    ),
    store.onnuri ? el('p', { class: 'source-note', text: `온누리상품권 가맹 정보는 소상공인시장진흥공단 가맹점 현황(${store.onnuri.dataDate} 기준)이에요. 2026년 하반기 상품권 개편으로 달라질 수 있으니 결제 전에 가게에 확인해 주세요.` }) : null,
    store.history ? el('section', {}, el('h2', { text: '가게 이야기' }), el('p', { text: store.history })) : null,
    el('div', { class: 'actions' },
      el('a', { class: 'button-primary wide', href: directionsUrl(store), target: '_blank', rel: 'noopener' }, icon('directions', 'icon-small'), '길찾기'),
      store.phone
        ? el('a', { class: 'button-secondary', href: `tel:${store.phone}`, text: '전화하기' })
        : el('button', { type: 'button', class: 'button-secondary', disabled: '', text: '전화번호 없음' }),
      store.kakaoMapUrl
        ? el('a', { class: 'button-secondary', href: store.kakaoMapUrl, target: '_blank', rel: 'noopener', 'aria-label': '카카오맵에서 보기 (사진·리뷰)', text: '카카오맵' })
        : null,
      reportButton,
    ),
    el('p', { id: 'reportNotice', class: 'report-notice', role: 'status', hidden: '' }),
    el('section', { id: 'nearMarkets', class: 'near-markets', 'aria-labelledby': 'nearMarketsTitle', hidden: '' }),
  ].filter(Boolean));
}

// ---------------------------------------------------------------- 가까운 전통시장 (MKT-01)

const NEAR_MARKET_RADIUS = 300;

async function loadNearMarkets(store) {
  if (store.latitude == null || store.longitude == null) return;
  let markets;
  try {
    markets = await api.nearbyMarkets({ lat: store.latitude, lng: store.longitude, radius: NEAR_MARKET_RADIUS });
  } catch (_) {
    return;   // 시장 정보는 덤이라, 실패하면 조용히 넘어간다
  }
  if (!markets.length) return;
  const box = document.getElementById('nearMarkets');
  box.replaceChildren(
    el('h2', { id: 'nearMarketsTitle', text: `가까운 전통시장 (${NEAR_MARKET_RADIUS}m 안)` }),
    el('ul', {}, ...markets.slice(0, 5).map((m) => el('li', {},
      el('a', { href: `/market.html?id=${m.id}`, class: 'near-market' },
        el('span', { class: 'near-market-name' }, el('span', { class: 'badge-market', text: '전통시장' }), el('strong', { text: m.name })),
        el('span', { class: 'near-market-meta', text: `${formatDistance(m.distanceM)} · ${marketDaySummary(m)}` }),
      ))),
    ),
  );
  box.hidden = false;
}

// ---------------------------------------------------------------- 영업 정보 제보

const dialog = document.getElementById('reportDialog');
const form = document.getElementById('reportForm');
const $ = (id) => document.getElementById(id);

function reportReturnUrl(store) {
  return `/store.html?id=${store.id}#report`;
}

function setReportError({ alert = '', type = '', content = '' } = {}) {
  $('reportAlert').textContent = alert;
  $('reportAlert').hidden = !alert;
  $('typeError').textContent = type;
  $('contentError').textContent = content;
  $('reportContent').setAttribute('aria-invalid', content ? 'true' : 'false');
}

async function openReport(store) {
  const user = auth.getUser() ?? await auth.init();
  if (!user) {
    location.href = auth.loginUrl(reportReturnUrl(store));
    return;
  }
  form.reset();
  $('contentCount').textContent = '0';
  setReportError();
  $('reportStore').textContent = store.name;
  dialog.dataset.storeId = store.id;
  dialog.showModal();
  form.querySelector('input[name="type"]').focus();
}

function showReportNotice(message) {
  const notice = $('reportNotice');
  notice.replaceChildren(message, ' ', Object.assign(document.createElement('a'), { href: '/mypage.html#reports', textContent: '내 제보 보기' }));
  notice.hidden = false;
}

$('reportContent').addEventListener('input', () => {
  $('contentCount').textContent = String($('reportContent').value.length);
});
$('reportCancel').addEventListener('click', () => dialog.close());
// 종류를 고르면 '골라 주세요' 안내를 지운다
form.addEventListener('change', (event) => {
  if (event.target.name === 'type') $('typeError').textContent = '';
});

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const type = form.type.value;
  const content = $('reportContent').value.trim();
  if (!type) {
    setReportError({ type: '어떤 정보가 다른지 골라 주세요.' });
    form.querySelector('input[name="type"]').focus();
    return;
  }
  setReportError();
  $('reportSubmit').disabled = true;
  try {
    await auth.sendStoreReport(dialog.dataset.storeId, { type, content });
    dialog.close();
    showReportNotice('제보를 보냈어요. 확인한 뒤 반영할게요.');
    $('reportButton').focus();
  } catch (e) {
    if (e.code === 'UNAUTHORIZED' || e.code === 'TOKEN_EXPIRED' || e.code === 'TOKEN_INVALID') {
      location.href = auth.loginUrl(`/store.html?id=${dialog.dataset.storeId}#report`);
    } else if (e.code === 'INVALID_INPUT' && e.details?.some((d) => d.includes('500자'))) {
      setReportError({ content: e.details.find((d) => d.includes('500자')) });
    } else {
      setReportError({ alert: e.message });
    }
  } finally {
    $('reportSubmit').disabled = false;
  }
});

async function init() {
  const id = new URLSearchParams(location.search).get('id');
  if (!id || !/^\d+$/.test(id)) {
    root.replaceChildren(el('p', { class: 'error', text: '가게 주소가 올바르지 않아요.' }));
    return;
  }
  let store;
  try {
    store = await api.storeDetail(id);
    render(store);
    loadNearMarkets(store);
  } catch (e) {
    root.replaceChildren(el('p', { class: 'error', text: '가게 정보를 불러오지 못했어요. 지도로 돌아가 다시 선택해 주세요.' }));
    return;
  }
  // 로그인하고 돌아온 경우(#report): 제보 창을 바로 연다
  if (location.hash === '#report') {
    history.replaceState(null, '', location.pathname + location.search);
    if (await auth.init()) openReport(store);
  }
}

mountAccount(document.getElementById('account'));
auth.init();
init();
