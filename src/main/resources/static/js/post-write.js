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

// ---------------------------------------------------------------- 사진 (COM-02)
// 고르면 바로 한 장씩 올리고(번호를 받음), 글을 올릴 때 번호와 설명을 함께 보낸다.
const MAX_PHOTOS = 4;
const MAX_SIDE = 1600;
let photos = [];   // { key, id, src, alt, state: 'uploading' | 'done' | 'error', message, local }
let photoSeq = 0;

/** 기기 안에서 긴 변 1600px JPG로 줄인다. 휴대폰 사진의 방향도 바로잡힌다. 못 그리면(브라우저가 모르는 형식) 원본 그대로 */
async function shrink(file) {
  let bmp;
  try {
    bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch (_) {
    return file;
  }
  const scale = Math.min(1, MAX_SIDE / Math.max(bmp.width, bmp.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(bmp.width * scale));
  canvas.height = Math.max(1, Math.round(bmp.height * scale));
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(bmp, 0, 0, canvas.width, canvas.height);
  bmp.close?.();
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.9));
  return blob ?? file;
}

function photoStatus(message) {
  $('photoStatus').textContent = message ?? '';
}

function renderPhotos() {
  const n = photos.length;
  $('photoList').replaceChildren(...photos.map((ph, i) => {
    const altId = `photoAlt-${ph.key}`;
    const alt = el('input', { id: altId, type: 'text', maxlength: '100', value: ph.alt ?? '', autocomplete: 'off', placeholder: '예: 시장 입구 간판' });
    alt.addEventListener('input', () => { ph.alt = alt.value; });
    const remove = el('button', { type: 'button', class: 'text-button', text: '빼기', 'aria-label': `사진 ${i + 1} 빼기` });
    remove.addEventListener('click', () => removePhoto(ph.key, i));
    const state = ph.state === 'uploading' ? '올리는 중…' : ph.state === 'error' ? ph.message : null;
    return el('li', { class: `photo-item${ph.state === 'error' ? ' has-error' : ''}`, 'data-key': String(ph.key) },
      el('img', { src: ph.src, alt: `고른 사진 ${i + 1}`, class: 'photo-thumb' }),
      el('div', { class: 'photo-item-body' },
        el('p', { class: 'photo-item-head' }, el('strong', { text: `사진 ${i + 1}/${n}` }), state ? el('span', { class: 'photo-state', text: ` · ${state}` }) : null, remove),
        ph.state === 'error' ? null : el('div', { class: 'photo-alt' },
          el('label', { for: altId, text: '사진 설명 (선택, 화면 낭독기가 읽어요)' }), alt)));
  }));
  const left = MAX_PHOTOS - photos.length;
  $('photoAdd').hidden = left <= 0;
  $('photoAddText').textContent = photos.length ? `사진 더 고르기 (${left}장 더)` : '사진 고르기';
}

function removePhoto(key, index) {
  const ph = photos.find((x) => x.key === key);
  if (ph?.local) URL.revokeObjectURL(ph.src);
  photos = photos.filter((x) => x.key !== key);
  renderPhotos();
  photoStatus(`사진 ${index + 1}을 뺐어요.`);
  // 다음 사진의 '빼기'로, 없으면 '사진 고르기'로 초점
  const next = $('photoList').querySelectorAll('.photo-item .text-button')[Math.min(index, photos.length - 1)];
  (next ?? $('photoInput')).focus();
}

async function addFiles(fileList) {
  $('photoError').textContent = '';
  const files = [...fileList];
  const left = MAX_PHOTOS - photos.length;
  if (files.length > left) $('photoError').textContent = `사진은 ${MAX_PHOTOS}장까지라 앞의 ${left}장만 골랐어요.`;
  const picked = files.slice(0, Math.max(0, left)).map((file) => {
    const ph = { key: ++photoSeq, id: null, src: URL.createObjectURL(file), local: true, alt: '', state: 'uploading', file };
    photos.push(ph);
    return ph;
  });
  if (!picked.length) return;
  renderPhotos();
  photoStatus(`사진 ${picked.length}장을 올리는 중이에요…`);
  await Promise.all(picked.map(async (ph) => {
    try {
      const blob = await shrink(ph.file);
      if (blob.size > 10 * 1024 * 1024) throw new Error('사진 한 장은 10MB까지 올릴 수 있어요.');
      const res = await posts.uploadPhoto(blob);
      ph.id = res.id;
      ph.state = 'done';
    } catch (err) {
      if (err.status === 401) { location.href = auth.loginUrl(); return; }
      ph.state = 'error';
      ph.message = err.message || '올리지 못했어요.';
    }
    delete ph.file;
    if (photos.includes(ph)) renderPhotos();
  }));
  const failed = picked.filter((ph) => ph.state === 'error' && photos.includes(ph)).length;
  photoStatus(failed ? `${picked.length - failed}장을 올렸고, ${failed}장은 올리지 못했어요. 못 올린 사진은 빼고 다시 골라 주세요.`
    : `사진 ${picked.length}장을 올렸어요. 글을 올리면 함께 보여요.`);
}

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
  $('photoError').textContent = '';
  if (!category()) fail('category', '말머리를 골라 주세요.', $('categoryOptions').querySelector('input'));
  if (title.length < 2) fail('title', '제목을 2자 이상 써 주세요.', $('title'));
  if (content.length < 5) fail('content', '내용을 5자 이상 써 주세요.', $('content'));
  if (photos.some((ph) => ph.state === 'uploading')) {
    $('photoError').textContent = '사진을 올리는 중이에요. 다 올라간 뒤에 다시 눌러 주세요.';
    first ??= $('photoInput');
  } else if (photos.some((ph) => ph.state === 'error')) {
    $('photoError').textContent = '올리지 못한 사진이 있어요. 그 사진을 빼고 다시 눌러 주세요.';
    first ??= $('photoList').querySelector('.has-error .text-button');
  }
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
    photos: photos.map((ph) => ({ id: ph.id, alt: (ph.alt ?? '').trim() || null })),
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
      photos = (p.photos ?? []).map((x) => ({ key: ++photoSeq, id: x.id, src: x.thumbUrl, alt: x.alt ?? '', state: 'done' }));
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
  $('photoInput').addEventListener('change', () => {
    const files = $('photoInput').files;
    if (files?.length) addFiles(files);
    $('photoInput').value = '';   // 같은 사진을 다시 고를 수 있게
  });
  renderPhotos();
  $('postForm').hidden = false;
}

init();
