// 동네 이야기 글쓰기·고치기 (COM-01)
// 새 글: /post-write.html?category=REVIEW&placeType=STORE&placeId=12 (가게 화면의 '이야기 쓰기')
// 고치기: /post-write.html?id=5
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { api } from './api.js';
import { el, posts, CATEGORIES, CATEGORY_HELP, PLACE_LABEL } from './community-common.js';

const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
const editId = /^\d+$/.test(params.get('id') ?? '') ? params.get('id') : null;
let place = null;   // { type, id, name }

function showAlert(message) {
  $('formAlert').textContent = message;
  $('formAlert').hidden = false;
  $('formAlert').focus();
}

function renderCategories(selected) {
  $('categoryOptions').replaceChildren(...CATEGORIES.map((c) => el('label', { class: 'cat-option' },
    el('input', { type: 'radio', name: 'category', value: c.code, checked: c.code === selected }),
    el('span', { class: 'cat-option-text' }, el('strong', { text: c.label }), el('span', { text: CATEGORY_HELP[c.code] })))));
  $('categoryOptions').addEventListener('change', updatePlaceBox);
}

const category = () => $('postForm').querySelector('input[name="category"]:checked')?.value ?? '';

/** 추천 글은 '이음에 없는 곳'을 알리는 글이라 장소를 붙이지 않는다 */
function updatePlaceBox() {
  const rec = category() === 'RECOMMEND';
  $('placeBox').hidden = rec;
  if (rec) setPlace(null);
}

function setPlace(p) {
  place = p;
  $('placeChosen').hidden = !p;
  $('placeSearch').hidden = !!p;
  $('placeResults').replaceChildren();
  $('placeStatus').textContent = '';
  if (p) $('placeChosenText').textContent = `${PLACE_LABEL[p.type]} · ${p.name}`;
}

async function findPlaces() {
  const q = $('placeQ').value.trim();
  if (q.length < 1) { $('placeStatus').textContent = '이름을 써 주세요.'; return; }
  $('placeStatus').textContent = '찾는 중...';
  try {
    const res = await fetch(`/api/search?${new URLSearchParams({ q })}`);
    if (!res.ok) throw new Error();
    const data = await res.json();
    const items = [
      ...data.stores.items.map((s) => ({ type: 'STORE', id: s.id, name: s.name, sub: s.roadAddress })),
      ...data.markets.items.map((m) => ({ type: 'MARKET', id: m.id, name: m.name, sub: m.roadAddress })),
      ...data.festivals.items.map((f) => ({ type: 'FESTIVAL', id: f.id, name: f.name, sub: [f.regionName, f.startDate].filter(Boolean).join(' · ') })),
    ];
    $('placeStatus').textContent = items.length ? `${items.length}곳을 찾았어요. 하나를 골라 주세요.` : `'${q}'에 맞는 곳이 없어요.`;
    $('placeResults').replaceChildren(...items.map((p) => {
      const b = el('button', { type: 'button', class: 'place-result' },
        el('span', { class: 'place-result-type', text: PLACE_LABEL[p.type] }),
        el('span', { class: 'place-result-name', text: p.name }),
        p.sub ? el('span', { class: 'place-result-sub', text: p.sub }) : null);
      b.addEventListener('click', () => { setPlace(p); $('placeClear').focus(); });
      return el('li', {}, b);
    }));
  } catch (_) {
    $('placeStatus').textContent = '찾지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
}

async function placeName(type, id) {
  try {
    if (type === 'STORE') return (await api.storeDetail(id)).name;
    if (type === 'MARKET') return (await api.market(id)).name;
    if (type === 'FESTIVAL') return (await api.festival(id)).name;
  } catch (_) { /* 없는 곳이면 붙이지 않음 */ }
  return null;
}

function counter(input, out) {
  const update = () => { out.textContent = String(input.value.length); };
  input.addEventListener('input', update);
  update();
}

function setError(id, message) {
  $(`${id}Error`).textContent = message ?? '';
  $(id)?.setAttribute('aria-invalid', String(!!message));
}

function validate() {
  const title = $('title').value.trim();
  const content = $('content').value.trim();
  let first = null;
  const fail = (id, msg, focus) => { setError(id, msg); first ??= focus; };
  setError('category'); setError('title'); setError('content');
  if (!category()) fail('category', '말머리를 골라 주세요.', $('categoryOptions').querySelector('input'));
  if (title.length < 2) fail('title', '제목을 2자 이상 써 주세요.', $('title'));
  if (content.length < 5) fail('content', '내용을 5자 이상 써 주세요.', $('content'));
  first?.focus();
  return !first;
}

async function submit(e) {
  e.preventDefault();
  $('formAlert').hidden = true;
  if (!validate()) return;
  const body = {
    category: category(),
    title: $('title').value.trim(),
    content: $('content').value.trim(),
    placeType: place?.type ?? null,
    placeId: place?.id ?? null,
  };
  $('submitButton').disabled = true;
  try {
    const saved = editId ? await posts.update(editId, body) : await posts.create(body);
    location.replace(`/post.html?id=${saved.id}&saved=1`);
  } catch (err) {
    $('submitButton').disabled = false;
    if (err.status === 401) { location.href = auth.loginUrl(); return; }
    showAlert(err.details?.length ? err.details.join(' ') : err.message);
  }
}

async function init() {
  mountAccount($('account'), { next: location.pathname + location.search });
  const user = await auth.init();
  if (!user) { location.replace(auth.loginUrl()); return; }
  let initialCategory = CATEGORIES.some((c) => c.code === params.get('category')) ? params.get('category') : '';

  if (editId) {
    try {
      const p = await posts.get(editId);
      if (!p.canEdit) { showAlert('내가 쓴 글만 고칠 수 있어요.'); return; }
      document.title = '글 고치기 - 이음';
      $('pageTitle').textContent = '글 고치기';
      $('submitButton').textContent = '고친 내용 올리기';
      $('cancelLink').href = `/post.html?id=${p.id}`;
      $('backLink').href = `/post.html?id=${p.id}`;
      initialCategory = p.category;
      $('title').value = p.title;
      $('content').value = p.content;
      if (p.place) setPlace({ type: p.place.type, id: p.place.id, name: p.place.name });
    } catch (err) {
      showAlert(err.message);
      return;
    }
  } else if (['STORE', 'MARKET', 'FESTIVAL'].includes(params.get('placeType')) && /^\d+$/.test(params.get('placeId') ?? '')) {
    const name = await placeName(params.get('placeType'), params.get('placeId'));
    if (name) setPlace({ type: params.get('placeType'), id: Number(params.get('placeId')), name });
  }

  renderCategories(initialCategory);
  updatePlaceBox();
  counter($('title'), $('titleCount'));
  counter($('content'), $('contentCount'));
  $('placeClear').addEventListener('click', () => { setPlace(null); $('placeQ').focus(); });
  $('placeFind').addEventListener('click', findPlaces);
  $('placeQ').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); findPlaces(); } });
  $('postForm').addEventListener('submit', submit);
  $('postForm').hidden = false;
}

init();
