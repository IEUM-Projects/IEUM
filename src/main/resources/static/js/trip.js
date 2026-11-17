// 걷기 코스 출발점 고르기 (TOUR-01): 현재 위치 또는 장소 검색 → course.html 로 이동
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

function useHere() {
  const button = $('useHere');
  $('hereError').textContent = '';
  if (!navigator.geolocation) {
    $('hereError').textContent = '이 브라우저는 위치 기능을 지원하지 않아요. 아래에서 장소를 찾아 주세요.';
    return;
  }
  button.disabled = true;
  button.setAttribute('aria-busy', 'true');
  navigator.geolocation.getCurrentPosition(
    (pos) => { location.href = courseUrl(pos.coords.latitude, pos.coords.longitude, '현재 위치'); },
    () => {
      button.disabled = false;
      button.removeAttribute('aria-busy');
      $('hereError').textContent = '위치 권한이 없어서 현재 위치를 알 수 없어요. 아래에서 장소를 찾아 주세요.';
    },
    { timeout: 8000, maximumAge: 300000 },
  );
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
      $('tripStatus').textContent = '지금은 장소를 찾을 수 없어요. 잠시 후 다시 시도하거나 현재 위치로 시작해 주세요.';
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
  $('useHere').addEventListener('click', useHere);
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
