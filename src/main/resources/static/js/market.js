// 전통시장 상세 (MKT-01): /market.html?id=1203
import { api, formatDistance, directionsUrl } from './api.js';
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { todayString } from './festival-format.js';
import { digitsOf, cycleLabel, daysOfMonth, isMarketDay, nextMarketDay, dayText } from './market-days.js';
import { facilityGroups } from './market-facilities.js';
import { photoCredit } from './festival-format.js';
import { NEWS_NOTE, newsItem } from './news-common.js';

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

const ICONS = {
  market: '<path d="M3 10l2-6h14l2 6"/><path d="M3 10c0 1.7 1.3 3 3 3s3-1.3 3-3c0 1.7 1.3 3 3 3s3-1.3 3-3c0 1.7 1.3 3 3 3s3-1.3 3-3"/><path d="M5 13v7h14v-7"/>',
  directions: '<path d="M3 11l18-8-8 18-2-8z"/>',
  link: '<path d="M10 14l4-4"/><path d="M8.5 16.5l-1 1a3.5 3.5 0 01-5-5l3-3a3.5 3.5 0 015 0"/><path d="M15.5 7.5l1-1a3.5 3.5 0 015 5l-3 3a3.5 3.5 0 01-5 0"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  walk: '<circle cx="13" cy="4.5" r="1.8"/><path d="M10 21l2-6 3 3v5M8 12l2.5-4.5 4 1.5 2 3.5M12 15l-1.5-3"/>',
  calendar: '<rect x="3.5" y="5" width="17" height="15" rx="2"/><path d="M3.5 10h17M8 3v4M16 3v4"/>',
};
function icon(name, className = 'icon-small') {
  const span = document.createElement('span');
  span.innerHTML = `<svg class="${className}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true">${ICONS[name]}</svg>`;
  return span.firstChild;
}

function row(label, value) {
  if (value == null || value === '') return null;
  return el('div', {}, el('dt', { text: label }), el('dd', {}, value));
}

/** 장날 상자: 매일 / 정기장 날짜 / 오늘·다음 장날 */
function marketDayBox(m) {
  const digits = digitsOf(m.marketDays);
  const today = todayString();
  if (!digits.length) {
    return el('section', { class: 'day-box', 'aria-labelledby': 'dayTitle' },
      el('h2', { id: 'dayTitle' }, icon('calendar'), '장날'),
      el('p', { class: 'day-main', text: m.permanent ? '매일 열려요' : '장날 정보가 없어요' }),
      el('p', { class: 'day-sub', text: '가게마다 쉬는 날이 다를 수 있어요.' }));
  }
  const todayIs = isMarketDay(digits, today);
  const next = todayIs ? today : nextMarketDay(digits, today);
  return el('section', { class: 'day-box', 'aria-labelledby': 'dayTitle' },
    el('h2', { id: 'dayTitle' }, icon('calendar'), '장날'),
    el('p', { class: 'day-main', text: m.permanent ? `매일 열고, ${cycleLabel(digits)}에 장이 서요` : `${cycleLabel(digits)}에 장이 서요` }),
    el('p', { class: `day-status ${todayIs ? 'today' : ''}`, text: todayIs ? '오늘 장 서는 날이에요' : next ? `다음 장날: ${dayText(next)}` : '' }),
    el('p', { class: 'day-sub', text: `매월 ${daysOfMonth(digits).join(', ')}일 · 31일은 시장마다 달라요` }));
}

/** 편의시설 상자 (DATA-01): 전통시장현황과 이어진 시장만. '있음'인 시설만 묶음별로 보여 준다 */
function facilityBox(m) {
  if (!m.facilities) return null;
  const groups = facilityGroups(m);
  return el('section', { class: 'facility-box', 'aria-labelledby': 'facilityTitle' },
    el('h2', { id: 'facilityTitle', text: '편의시설' }),
    ...groups.map((g) => el('div', { class: 'facility-group' },
      el('h3', { text: g.title }),
      g.items.length
        ? el('ul', { class: 'facility-list' }, ...g.items.map((label) => el('li', {}, icon('check', 'icon-xsmall'), label)))
        : el('p', { class: 'facility-none', text: '해당 시설이 없어요' }))),
    el('p', { class: 'facility-note', text: `표시하지 않은 시설은 없거나 정보가 없어요. 시장이 소상공인시장진흥공단에 알린 내용(${m.facilities.dataDate} 기준)이라 실제와 다를 수 있어요.` }));
}

function storeItem(s) {
  const unverified = s.infoStatus === 'UNVERIFIED';
  return el('li', {},
    el('a', { href: `/store.html?id=${s.id}`, class: 'near-item' },
      el('span', { class: 'near-name' },
        el('strong', { text: s.name }),
        unverified ? el('span', { class: 'badge-unverified', text: '영업 정보 미확인' }) : null),
      el('span', { class: 'near-meta', text: `${formatDistance(s.distanceM)} · ${s.category ?? '분류 없음'}` }),
    ));
}

function render(m) {
  document.title = `${m.name} - 이음`;
  const address = m.roadAddress ?? m.jibunAddress;
  const age = m.openedYear ? new Date().getFullYear() - m.openedYear : null;
  const hasCoords = m.latitude != null && m.longitude != null;

  root.replaceChildren(...[
    el('div', { class: 'title-block' },
      el('div', { class: 'badges' },
        el('span', { class: 'badge-market' }, icon('market', 'icon-xsmall'), '전통시장'),
        el('span', { class: 'badge-category', text: m.marketType }),
        m.regionName ? el('span', { class: 'badge-category', text: m.regionName }) : null,
      ),
      el('h1', { text: m.name }),
    ),
    marketDayBox(m),
    el('dl', { class: 'info' },
      row('주소', address),
      m.roadAddress && m.jibunAddress ? row('지번', m.jibunAddress) : null,
      row('점포 수', m.storeCount ? `${m.storeCount.toLocaleString()}개` : null),
      m.onnuri ? row('상품권', `온누리 가맹점 ${m.onnuri.merchantCount.toLocaleString()}곳 (디지털 상품권 ${m.onnuri.digitalCount.toLocaleString()}곳)`) : null,
      m.facilities ? null : row('편의시설', `주차장 ${m.hasParking ? '있음' : '없음'} · 공중화장실 ${m.hasToilet ? '있음' : '없음'}`),
      row('개설', m.openedYear ? `${m.openedYear}년${age > 0 ? ` (${age}년째)` : ''}` : null),
      m.goods.length ? row('취급품목', el('ul', { class: 'goods' }, ...m.goods.map((g) => el('li', { text: g })))) : null,
    ),
    facilityBox(m),
    m.sameBuilding.length ? el('section', { class: 'near', 'aria-labelledby': 'sameTitle' },
      el('h2', { id: 'sameTitle', text: '같은 건물의 시장' }),
      el('ul', { class: 'near-list' }, ...m.sameBuilding.map((o) => el('li', {},
        el('a', { href: `/market.html?id=${o.id}`, class: 'near-item' }, el('strong', { text: o.name })))))) : null,
    el('section', { class: 'near', 'aria-labelledby': 'nearTitle' },
      el('h2', { id: 'nearTitle', text: `근처 백년가게 (${m.nearbyRadiusM}m 안)` }),
      m.nearbyStores.length
        ? el('ul', { class: 'near-list' }, ...m.nearbyStores.map(storeItem))
        : el('p', { class: 'empty', text: hasCoords ? `반경 ${m.nearbyRadiusM}m 안에 백년가게가 없어요.` : '시장 위치를 찾지 못해서 근처 가게를 보여 드릴 수 없어요.' }),
    ),
    hasCoords ? el('section', { id: 'tourBox', class: 'near tour-box', 'aria-labelledby': 'tourTitle' },
      el('h2', { id: 'tourTitle', text: '주변 관광지 (2km 안)' }),
      el('p', { id: 'tourStatus', class: 'empty', role: 'status', text: '불러오는 중...' }),
      el('ul', { id: 'tourList', class: 'near-list' }),
      el('a', { class: 'button-secondary wide course-link', href: `/course.html?market=${m.id}` }, icon('walk'), '이 시장에서 걷기 코스 만들기')) : null,
    el('section', { id: 'newsBox', class: 'near news-box', 'aria-labelledby': 'newsTitle' },
      el('h2', { id: 'newsTitle', text: '최근 기사' }),
      el('p', { id: 'newsStatus', class: 'empty', role: 'status', text: '불러오는 중...' }),
      el('ul', { id: 'newsList', class: 'news-list' }),
      el('p', { class: 'news-note', text: `${NEWS_NOTE} 이름이 같은 다른 곳의 기사가 섞일 수 있어요.` })),
    el('div', { class: 'actions' },
      hasCoords ? el('a', { class: 'button-primary wide', href: directionsUrl(m), target: '_blank', rel: 'noopener' }, icon('directions'), '길찾기') : null,
      m.homepageUrl ? el('a', { class: 'button-secondary wide', href: m.homepageUrl, target: '_blank', rel: 'noopener noreferrer' }, icon('link'), '홈페이지') : null,
    ),
    el('p', { class: 'source', text: `시장 정보: 전국전통시장표준데이터 (소상공인시장진흥공단)${m.dataDate ? ` · 기준일 ${m.dataDate}` : ''}` }),
    m.facilities ? el('p', { class: 'source', text: `편의시설: 전통시장현황 (소상공인시장진흥공단) · 기준일 ${m.facilities.dataDate}` }) : null,
    m.onnuri ? el('p', { class: 'source', text: `온누리상품권: 전국 온누리상품권 가맹점 현황 (소상공인시장진흥공단) · 기준일 ${m.onnuri.dataDate} · 2026년 하반기 개편으로 달라질 수 있어요` }) : null,
  ].filter(Boolean));
}

/** 주변 관광지 (TOUR-01): 관광공사 정보가 느릴 수 있어서 화면을 먼저 보여 주고 나중에 채운다 */
async function loadTour(m) {
  const status = document.getElementById('tourStatus');
  if (!status) return;
  try {
    const res = await fetch(`/api/tour/nearby?lat=${m.latitude}&lng=${m.longitude}&radius=2000`);
    if (!res.ok) throw new Error();
    const data = await res.json();
    const spots = data.spots.slice(0, 8);
    if (!spots.length) {
      status.textContent = data.available ? '2km 안에 관광공사에 등록된 관광지가 없어요.' : '관광지 정보를 불러오지 못했어요.';
      return;
    }
    status.textContent = `${data.source} · 가까운 순`;
    status.className = 'tour-source';
    document.getElementById('tourList').replaceChildren(...spots.map((t) => el('li', { class: 'tour-item' },
      t.imageUrl ? el('figure', { class: 'tour-photo' }, el('img', { src: t.imageUrl, alt: '', loading: 'lazy' }), el('figcaption', { text: photoCredit(t.imageLicense) })) : null,
      el('div', {},
        el('strong', { text: t.title }),
        el('p', { class: 'near-meta', text: `${t.typeName} · ${t.distanceM.toLocaleString()}m${t.address ? ` · ${t.address}` : ''}` })))));
  } catch (_) {
    status.textContent = '관광지 정보를 불러오지 못했어요.';
  }
}

/** 최근 기사 (NEWS-01): 네이버 뉴스 검색이 느릴 수 있어서 화면을 먼저 보여 주고 나중에 채운다 */
async function loadNews(m) {
  const status = document.getElementById('newsStatus');
  try {
    const res = await fetch(`/api/news/market/${m.id}?size=5`);
    if (!res.ok) throw new Error();
    const data = await res.json();
    document.getElementById('newsList').replaceChildren(...data.items.map(newsItem));
    status.textContent = data.items.length
      ? `'${data.query}' 최신 기사`
      : data.available ? `'${data.query}' 기사가 아직 없어요.` : '지금은 기사를 불러올 수 없어요.';
  } catch (_) {
    status.textContent = '기사를 불러오지 못했어요.';
  }
}

async function init() {
  mountAccount(document.getElementById('account'));
  auth.init();
  const id = new URLSearchParams(location.search).get('id');
  if (!id || !/^\d+$/.test(id)) {
    root.replaceChildren(el('p', { class: 'error', text: '시장 주소가 올바르지 않아요.' }));
    return;
  }
  try {
    const m = await api.market(id);
    render(m);
    if (m.latitude != null && m.longitude != null) loadTour(m);
    loadNews(m);
  } catch (e) {
    root.replaceChildren(el('p', { class: 'error', text: '시장 정보를 불러오지 못했어요. 지도로 돌아가 다시 선택해 주세요.' }));
  }
}

init();
