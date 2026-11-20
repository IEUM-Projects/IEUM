// 걷기 코스 출발점 고르기 (TOUR-01): 장소 검색 → course.html 로 이동 (POL-01: 현재 위치 출발은 뺌 - 위치를 서버로 보내지 않으려고)
import * as auth from './auth.js';
import { mountAccount } from './header.js';

const $ = (id) => document.getElementById(id);

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([key, value]) => {
    if (value === null || value === undefined || value === false) return;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value === true ? '' : value);
  });
  children.flat().filter((c) => c !== null && c !== undefined && c !== false && c !== '').forEach((c) => node.append(c));
  return node;
}

export function courseUrl(lat, lng, label, from = 'trip') {
  return `/course.html?${new URLSearchParams({ lat: Number(lat).toFixed(6), lng: Number(lng).toFixed(6), label, from })}`;
}

let request = 0;

async function search(q) {
  const query = q.trim().replace(/\s+/g, ' ');
  if (query.length < 2) {
    $('tripStatus').textContent = '검색어를 2자 이상 넣어 주세요.';
    $('tripResults').replaceChildren();
    $('tripQ').focus();
    return;
  }
  const mine = ++request;
  history.replaceState(null, '', `/trip.html?${new URLSearchParams({ q: query })}`);
  $('tripStatus').textContent = '찾는 중...';
  $('tripResults').replaceChildren();
  try {
    const res = await fetch(`/api/places/search?${new URLSearchParams({ q: query })}`);
    if (!res.ok) throw new Error();
    const data = await res.json();
    if (mine !== request) return;
    if (!data.available) {
      $('tripStatus').textContent = '지금은 장소를 찾을 수 없어요. 잠시 후 다시 시도해 주세요.';
      return;
    }
    if (!data.places.length) {
      $('tripStatus').textContent = `'${query}'(으)로 찾은 장소가 없어요. 다른 이름으로 찾아 보세요.`;
      return;
    }
    $('tripStatus').textContent = `'${query}' 장소 ${data.places.length}곳`;
    $('tripResults').replaceChildren(...data.places.map((p) => el('li', {},
      el('a', { class: 'trip-result', href: courseUrl(p.lat, p.lng, p.name) },
        el('span', { class: 'trip-result-text' },
          el('strong', { text: p.name }),
          el('span', { class: 'trip-result-meta', text: [p.category, p.address].filter(Boolean).join(' · ') })),
        el('span', { class: 'trip-result-go', text: '여기서 출발' })))));
  } catch (_) {
    if (mine === request) $('tripStatus').textContent = '장소를 찾지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
}

function init() {
  mountAccount($('account'));
  auth.init();
  $('tripSearch').addEventListener('submit', (event) => {
    event.preventDefault();
    search($('tripQ').value);
  });
  const q = new URLSearchParams(location.search).get('q');
  if (q) {
    $('tripQ').value = q.slice(0, 30);
    search(q.slice(0, 30));
  }
}

init();
