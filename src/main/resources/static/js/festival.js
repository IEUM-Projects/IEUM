// 축제 상세 (FES-01): /festival.html?id=12
import { api, routeAttrs, routeNote } from './api.js';
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { appendPlaceStories } from './place-stories.js';
import { longDate, status, photoCredit } from './festival-format.js';
import { notFoundBlock } from './not-found.js';

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
  directions: '<path d="M3 11l18-8-8 18-2-8z"/>',
  link: '<path d="M10 14l4-4"/><path d="M8.5 16.5l-1 1a3.5 3.5 0 01-5-5l3-3a3.5 3.5 0 015 0"/><path d="M15.5 7.5l1-1a3.5 3.5 0 015 5l-3 3a3.5 3.5 0 01-5 0"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 7.5v.5"/>',
};
function icon(name, className = 'icon-small') {
  const span = document.createElement('span');
  span.innerHTML = `<svg class="${className}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">${ICONS[name]}</svg>`;
  return span.firstChild;
}

function row(label, value, node) {
  if (!value && !node) return null;
  return el('div', {}, el('dt', { text: label }), el('dd', {}, node ?? value));
}

/** 홈페이지 주소를 짧게: https://www.festival.go.kr/a/b → festival.go.kr */
function siteName(url) {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch (_) {
    return url;
  }
}

/** 카카오맵 길찾기 링크 속성. 좌표가 있으면 공통 길찾기(휴대폰은 앱), 없으면 주소 검색 */
function directionsAttrs(f) {
  if (f.latitude != null && f.longitude != null) return routeAttrs(f);
  const address = f.roadAddress ?? f.jibunAddress;
  return address ? { href: `https://map.kakao.com/link/search/${encodeURIComponent(address)}`, target: '_blank', rel: 'noopener' } : null;
}

function sourceText(f) {
  const parts = [];
  if (f.source === 'STANDARD' || f.source === 'BOTH') parts.push('전국문화축제표준데이터');
  if (f.source === 'TOUR' || f.source === 'BOTH') parts.push('한국관광공사');
  const provider = f.provider && !parts.includes(f.provider) ? ` (${f.provider})` : '';
  const date = f.dataDate ? ` · 기준일 ${f.dataDate}` : '';
  return `정보 출처: ${parts.join(' · ')}${provider}${date}`;
}

function render(f) {
  document.title = `${f.name} - 이음`;
  // 돌아가기: 이 축제가 있는 달의 달력으로
  document.getElementById('backLink').href = `/festivals.html?month=${f.startDate.slice(0, 7)}&date=${f.startDate}`;
  const s = status(f);
  const address = f.roadAddress ?? f.jibunAddress;
  const directions = directionsAttrs(f);

  root.replaceChildren(...[
    el('div', { class: 'title-block' },
      el('div', { class: 'badges' },
        el('span', { class: `status ${s.cls}`, text: s.text }),
        f.longTerm ? el('span', { class: 'badge-category', text: '장기 행사' }) : null,
        f.regionName ? el('span', { class: 'badge-category', text: f.regionName }) : null,
      ),
      el('h1', { text: f.name }),
      el('p', { class: 'period-large', text: f.startDate === f.endDate ? longDate(f.startDate) : `${longDate(f.startDate)} ~ ${longDate(f.endDate)}` }),
    ),
    // 사진은 자르거나 바꾸지 않고 비율 그대로 보여 주고, 출처와 이용 조건을 함께 적는다
    f.imageUrl ? el('figure', { class: 'festival-photo' },
      el('img', { src: f.imageUrl, alt: `${f.name} 대표 사진`, loading: 'lazy', referrerpolicy: 'no-referrer' }),
      el('figcaption', { text: photoCredit(f.imageLicense) }),
    ) : null,
    el('dl', { class: 'info' },
      row('장소', f.place),
      row('주소', address),
      row('전화', f.phone, f.phone ? el('a', { href: `tel:${f.phone.replace(/[^0-9+]/g, '')}`, text: f.phone }) : null),
      // 홈페이지는 버튼 대신 정보 줄에 (아래 버튼은 길찾기·전화하기만)
      row('홈페이지', f.homepageUrl, f.homepageUrl ? el('a', { href: f.homepageUrl, target: '_blank', rel: 'noopener noreferrer', text: siteName(f.homepageUrl) }) : null),
      row('주최', f.host),
      row('주관', f.organizer),
      row('후원', f.sponsor),
    ),
    f.description ? el('section', { class: 'description' }, el('h2', { text: '축제 소개' }), el('p', { text: f.description })) : null,
    el('div', { class: 'actions' },
      directions ? el('a', { class: 'button-primary wide', ...directions }, icon('directions'), '길찾기') : null,
      directions ? el('p', { class: 'route-note', text: directions['data-route-ep'] ? routeNote() : '카카오맵에서 주소로 찾아요. 출발지를 정하면 길을 찾아요.' }) : null,
      // 전화하기는 늘 한 줄 전체. 번호가 없으면 누를 수 없는 '전화번호 없음' (가게 상세와 같게)
      f.phone
        ? el('a', { class: 'button-secondary phone-action', href: `tel:${f.phone.replace(/[^0-9+]/g, '')}` }, '전화하기')
        : el('button', { type: 'button', class: 'button-secondary phone-action', disabled: '', text: '전화번호 없음' }),
    ),
    el('p', { class: 'notice', role: 'note' }, icon('info'), '일정은 바뀔 수 있어요. 방문 전에 주최 측에 확인해 주세요.'),
    el('p', { class: 'source', text: sourceText(f) }),
  ].filter(Boolean));
}

function festivalMissing(notFound) {
  return notFoundBlock({
    notFound,
    title: '축제를 찾을 수 없어요',
    reason: '일정이 바뀌었거나 정보가 내려간 축제일 수 있어요.',
    failTitle: '축제 정보를 불러오지 못했어요',
    back: { href: '/festivals.html', label: '축제 달력 보기' },
  });
}

async function init() {
  mountAccount(document.getElementById('account'));
  auth.init();
  const id = new URLSearchParams(location.search).get('id');
  if (!id || !/^\d+$/.test(id)) {
    root.replaceChildren(festivalMissing(true));
    return;
  }
  try {
    const f = await api.festival(id);
    render(f);
    appendPlaceStories(root, { type: 'FESTIVAL', id: f.id, name: f.name });
  } catch (e) {
    root.replaceChildren(festivalMissing(e.status === 404));
  }
}

init();
