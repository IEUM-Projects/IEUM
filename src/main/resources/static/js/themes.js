// 테마 목록 (THM-01)
import { mountAccount } from './header.js';
import * as auth from './auth.js';
import { THEME_ICONS, countText, homePlace, placeLinkQuery, placeQuery, svgIcon } from './theme-common.js';

const $ = (id) => document.getElementById(id);

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([key, value]) => {
    if (value === null || value === undefined || value === false) return;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value);
  });
  children.flat().filter((c) => c !== null && c !== undefined && c !== false).forEach((child) => node.append(child));
  return node;
}

async function init() {
  mountAccount($('account'));
  auth.init();
  const place = homePlace();
  try {
    const res = await fetch(`/api/themes?${placeQuery(place)}`);
    if (!res.ok) throw new Error();
    const themes = await res.json();
    $('themeList').replaceChildren(...themes.map((t) => el('li', {},
      el('a', { class: `theme-card card-${t.id}`, href: `/theme.html?id=${t.id}${place ? `&${placeLinkQuery(place)}` : ''}` },
        el('span', { class: 'theme-card-icon' }, svgIcon(THEME_ICONS[t.id])),
        el('span', { class: 'theme-card-text' },
          el('strong', { class: 'theme-card-title', text: t.title }),
          el('span', { class: 'theme-card-desc', text: t.description }),
          el('span', { class: 'theme-card-count', text: countText(t, place) }))))));
    $('listStatus').textContent = place ? '' : '홈에서 위치를 고르면 가까운 곳이 몇 곳인지도 보여 드려요.';
  } catch (_) {
    $('listStatus').textContent = '테마를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
}

init();
