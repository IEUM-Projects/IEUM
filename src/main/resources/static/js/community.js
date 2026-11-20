// 동네 이야기 목록 (COM-01): 말머리·검색·장소로 거르기, 20개씩 더 보기
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { el, posts, postCard, PLACE_LABEL } from './community-common.js';

const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
const state = {
  category: params.get('category') ?? '',
  q: params.get('q') ?? '',
  placeType: params.get('placeType') ?? '',
  placeId: /^\d+$/.test(params.get('placeId') ?? '') ? params.get('placeId') : '',
  placeName: params.get('placeName') ?? '',
  page: 0,
};

function syncUrl() {
  const p = new URLSearchParams();
  ['category', 'q', 'placeType', 'placeId', 'placeName'].forEach((k) => { if (state[k]) p.set(k, state[k]); });
  history.replaceState(null, '', `/community.html${p.toString() ? `?${p}` : ''}`);
}

async function load(append = false) {
  if (!append) { state.page = 0; $('postList').replaceChildren(); }
  $('moreButton').hidden = true;
  $('listStatus').textContent = '불러오는 중...';
  try {
    const data = await posts.list(state);
    const cards = data.items.map((p) => postCard(p));
    $('postList').append(...cards);
    $('listStatus').textContent = data.total
      ? `${data.total.toLocaleString()}개의 이야기`
      : (state.q ? `'${state.q}'에 맞는 이야기가 없어요.` : '아직 이야기가 없어요. 첫 이야기를 남겨 주세요.');
    $('moreButton').hidden = !data.hasNext;
    if (append) cards[0]?.querySelector('a')?.focus();
  } catch (e) {
    $('listStatus').textContent = e.message;
  }
}

function setCategory(cat) {
  state.category = cat;
  document.querySelectorAll('.cat-filter button').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.cat === cat)));
  syncUrl();
  load();
}

function renderPlaceFilter() {
  if (!state.placeType || !state.placeId) return;
  const name = state.placeName || `${PLACE_LABEL[state.placeType] ?? ''} ${state.placeId}`;
  $('placeFilter').replaceChildren(`'${name}' 이야기만 보고 있어요. `,
    el('a', { href: '/community.html', text: '모든 이야기 보기' }));
  $('placeFilter').hidden = false;
  const w = new URLSearchParams({ category: 'REVIEW', placeType: state.placeType, placeId: state.placeId });
  $('writeLink').href = `/post-write.html?${w}`;
}

async function init() {
  mountAccount($('account'), { next: location.pathname + location.search });
  auth.init();
  $('q').value = state.q;
  document.querySelectorAll('.cat-filter button').forEach((b) => {
    b.setAttribute('aria-pressed', String(b.dataset.cat === state.category));
    b.addEventListener('click', () => setCategory(b.dataset.cat));
  });
  $('searchForm').addEventListener('submit', (e) => {
    e.preventDefault();
    state.q = $('q').value.trim();
    syncUrl();
    load();
  });
  $('moreButton').addEventListener('click', () => { state.page += 1; load(true); });
  renderPlaceFilter();
  load();
}

init();
