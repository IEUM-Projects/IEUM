// 관리자: 장소 정보 (DATA-05) — 좌표(브이월드)·카카오맵 장소 ID·분류 확인하고 고치기
// 카카오 검색 결과는 화면에 보여 주기만 한다 (저장하는 것은 고른 장소의 ID뿐)
import * as auth from './auth.js';
import { mountAccount } from './header.js';

const $ = (id) => document.getElementById(id);
const TABS = {
  REVIEW: { label: '확인 필요', help: '자동 연결로 카카오맵 장소를 정하지 못한 가게예요. 후보를 열어 보고 맞는 곳을 잇거나, 없으면 "카카오맵에 없음"을 눌러 주세요.' },
  NO_COORD: { label: '좌표 없음', help: '주소로 좌표를 찾지 못해 지도에 나오지 않는 가게예요. 주소의 오타나 상세 주소를 고쳐 다시 찾아 주세요.' },
  NO_CATEGORY: { label: '분류 없음', help: '분류가 비어 있는 가게예요. 인허가 파일(폐업 의심 화면)을 올리면 음식점·카페는 자동으로 채워지고, 나머지는 여기서 골라 주세요.' },
  LINKED: { label: '카카오맵 연결됨', help: '카카오맵 장소와 이어진 가게예요. 가게 화면의 "카카오맵" 버튼과 전화번호에 쓰여요.' },
  NO_PLACE: { label: '카카오맵에 없음', help: '카카오맵에서 찾을 수 없다고 정한 가게예요.' },
};
const TYPE = { CENTURY: '백년가게', CREATOR: '로컬크리에이터' };
const GEO = { OK: '좌표 있음', PENDING: '좌표 찾기 전', FAILED: '좌표 못 찾음' };
const ENRICH = { LINKED: '카카오맵 연결됨', REVIEW: '확인 필요', PENDING: '연결 전', NO_PLACE: '카카오맵에 없음' };
const state = { tab: 'REVIEW', page: 0, type: '', q: '', categories: { CENTURY: [], CREATOR: [] }, current: null, opener: null, changed: false };

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([key, value]) => {
    if (value === null || value === undefined || value === false) return;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value === true ? '' : value);
  });
  children.flat().filter((c) => c !== null && c !== undefined && c !== false).forEach((child) => node.append(child));
  return node;
}

function toast(message, ms = 5000) {
  $('toast').textContent = message;
  $('toast').hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { $('toast').hidden = true; }, ms);
}

class ApiError extends Error {
  constructor(status, body) {
    super(body?.message ?? '잠시 후 다시 시도해 주세요.');
    this.status = status;
    this.details = body?.details ?? [];
  }
}

async function request(url, options = {}) {
  const res = await auth.authFetch(url, { ...options, headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}) } });
  const body = await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(res.status, body);
  return body;
}

const base = '/api/admin/places';
const api = {
  list: (s) => request(`${base}?tab=${s.tab}&type=${s.type}&q=${encodeURIComponent(s.q)}&page=${s.page}`),
  candidates: (id, q) => request(`${base}/${id}/candidates?q=${encodeURIComponent(q)}`),
  link: (id, placeId) => request(`${base}/${id}/place`, { method: 'PUT', body: JSON.stringify({ placeId }) }),
  unlink: (id) => request(`${base}/${id}/place`, { method: 'DELETE' }),
  noPlace: (id) => request(`${base}/${id}/no-place`, { method: 'POST' }),
  category: (id, category) => request(`${base}/${id}/category`, { method: 'PUT', body: JSON.stringify({ category }) }),
  address: (id, roadAddress) => request(`${base}/${id}/address`, { method: 'PUT', body: JSON.stringify({ roadAddress }) }),
  jobs: () => request(`${base}/jobs`),
  start: (kind) => request(`${base}/jobs/${kind}`, { method: 'POST' }),
};

function showGate(message) {
  $('gate').textContent = message;
  $('gate').hidden = false;
  $('workspace').hidden = true;
}

function handleAuthError(e) {
  if (e.status === 401) { location.replace(auth.loginUrl('/admin-places.html')); return true; }
  if (e.status === 403) { showGate('관리자만 볼 수 있는 화면이에요. 관리자 권한을 받았다면 로그아웃했다가 다시 로그인해 주세요.'); return true; }
  return false;
}

// ---------------------------------------------------------------- 자동 채우기 진행 상황

function jobText(j, name) {
  if (!j) return '';
  if (j.state === 'RUNNING') return `${name}: 하는 중 (${j.done}/${j.total})`;
  return `${name}: ${j.message ?? ''}`;
}

async function loadJobs() {
  try {
    const j = await api.jobs();
    $('jobGeocode').textContent = j.vworldAvailable ? jobText(j.geocode, '좌표 찾기')
      : '좌표 찾기: 브이월드 키(vworld.api-key)가 없어서 할 수 없어요.';
    $('jobLink').textContent = j.kakaoAvailable ? jobText(j.link, '카카오맵 연결')
      : '카카오맵 연결: 카카오 REST API 키가 없어서 할 수 없어요.';
    const running = j.geocode?.state === 'RUNNING' || j.link?.state === 'RUNNING';
    $('geocodeButton').disabled = running || !j.vworldAvailable;
    $('linkButton').disabled = running || !j.kakaoAvailable;
    clearTimeout(loadJobs.timer);
    if (running) {
      loadJobs.wasRunning = true;
      loadJobs.timer = setTimeout(loadJobs, 3000);
    } else if (loadJobs.wasRunning) {
      loadJobs.wasRunning = false;
      loadList();   // 끝났으면 목록 숫자를 새로
    }
  } catch (e) {
    handleAuthError(e);
  }
}

async function startJob(kind) {
  try {
    await api.start(kind);
    toast(kind === 'geocode' ? '못 찾은 좌표를 다시 찾기 시작했어요.' : '카카오맵 자동 연결을 시작했어요.');
  } catch (e) {
    if (!handleAuthError(e)) toast(e.message);
  }
  loadJobs();
}

// ---------------------------------------------------------------- 목록

function rowMeta(p) {
  return [p.region ?? '지역 모름', p.roadAddress ?? p.addressRaw ?? '주소 없음', p.category ?? '분류 없음'].join(' · ');
}

function row(p) {
  const edit = el('button', { type: 'button', class: 'button-secondary', text: '확인·고치기', 'aria-label': `${p.name} 장소 정보 확인·고치기` });
  edit.addEventListener('click', () => openDialog(p, edit));
  return el('li', { class: 'admin-row notice-admin-row place-row', 'data-id': p.id },
    el('div', { class: 'report-head' },
      el('span', { class: 'type-badge', text: TYPE[p.type] ?? p.type }),
      el('span', { class: `status-badge st-place-${p.enrichStatus.toLowerCase()}`, text: ENRICH[p.enrichStatus] ?? p.enrichStatus }),
      p.hasCoord ? null : el('span', { class: 'status-badge st-place-nocoord', text: GEO[p.geocodeStatus] ?? '좌표 없음' }),
      p.visible ? null : el('span', { class: 'status-badge st-hidden', text: '지도에 숨김' }),
      el('h2', { text: p.name })),
    el('p', { class: 'story-row-meta', text: rowMeta(p) }),
    el('div', { class: 'row-actions' }, edit,
      p.kakaoMapUrl ? el('a', { class: 'button-secondary', href: p.kakaoMapUrl, target: '_blank', rel: 'noopener', text: '카카오맵', 'aria-label': `${p.name} 카카오맵 (새 창)` }) : null,
      el('a', { class: 'button-secondary', href: `/store.html?id=${p.id}`, target: '_blank', rel: 'noopener', text: '가게 화면', 'aria-label': `${p.name} 가게 화면 (새 창)` })));
}

async function loadList(append = false) {
  if (!append) { state.page = 0; $('list').replaceChildren(); }
  $('listStatus').textContent = '불러오는 중...';
  $('moreButton').hidden = true;
  try {
    const data = await api.list(state);
    state.categories = { CENTURY: data.centuryCategories, CREATOR: data.creatorCategories };
    Object.entries(data.counts).forEach(([k, n]) => { $(`count-${k}`).textContent = String(n); });
    const rows = data.items.map(row);
    $('list').append(...rows);
    $('listStatus').textContent = data.total ? `${TABS[state.tab].label} ${data.total}곳` : `${TABS[state.tab].label}인 가게가 없어요.`;
    $('moreButton').hidden = !data.hasNext;
    if (append) rows[0]?.querySelector('button')?.focus();
    $('workspace').hidden = false;
    $('gate').hidden = true;
  } catch (e) {
    if (!handleAuthError(e)) $('listStatus').textContent = e.message;
  }
}

function selectTab(tab, focus) {
  state.tab = tab;
  document.querySelectorAll('[role="tab"]').forEach((t) => {
    const on = t.dataset.tab === tab;
    t.setAttribute('aria-selected', String(on));
    t.tabIndex = on ? 0 : -1;
    if (on && focus) t.focus();
  });
  $('panel').setAttribute('aria-labelledby', `tab-${tab}`);
  $('tabHelp').textContent = TABS[tab].help;
  loadList();
}

// ---------------------------------------------------------------- 고치기 창

function showAlert(message, details = []) {
  const box = $('dialogAlert');
  box.replaceChildren(message, ...(details.length ? [el('ul', {}, details.map((d) => el('li', { text: d })))] : []));
  box.hidden = false;
  box.focus();
}

function fact(label, value) {
  return el('div', {}, el('dt', { text: label }), el('dd', { text: value ?? '없음' }));
}

function fill(p) {
  state.current = p;
  $('dialogTitle').textContent = p.name;
  $('placeFacts').replaceChildren(
    fact('종류', TYPE[p.type]),
    fact('원본 주소', p.addressRaw),
    fact('지역', p.region ?? '모름 (지역 표에 없거나 좌표 없음)'),
    fact('좌표', GEO[p.geocodeStatus] ?? p.geocodeStatus),
  );
  $('kakaoNow').replaceChildren(p.kakaoPlaceId
    ? el('span', {}, '지금 연결: ', el('a', { href: p.kakaoMapUrl, target: '_blank', rel: 'noopener', text: `장소 ${p.kakaoPlaceId} (새 창)` }))
    : `지금 연결: 없음 (${ENRICH[p.enrichStatus] ?? p.enrichStatus})`);
  $('unlinkButton').hidden = !p.kakaoPlaceId;
  $('noPlaceButton').hidden = p.enrichStatus === 'NO_PLACE';
  $('roadAddress').value = p.roadAddress ?? p.addressRaw ?? '';
  const options = state.categories[p.type] ?? [];
  $('category').replaceChildren(el('option', { value: '', text: '분류 없음' }),
    ...options.map((c) => el('option', { value: c, text: c, selected: c === p.category })));
  $('storeLink').href = `/store.html?id=${p.id}`;
}

async function loadCandidates(q = '') {
  const p = state.current;
  $('candList').replaceChildren();
  $('candStatus').textContent = '카카오맵에서 찾는 중...';
  try {
    const c = await api.candidates(p.id, q);
    $('candQ').value = c.query;
    if (!c.available) { $('candStatus').textContent = '지금은 카카오맵을 검색할 수 없어요 (키 또는 연결 문제).'; return; }
    $('candStatus').textContent = c.places.length ? `후보 ${c.places.length}곳 (카카오맵 검색 결과, 저장하지 않아요)` : '카카오맵에서 찾은 곳이 없어요. 이름을 바꿔 찾아보세요.';
    $('candList').replaceChildren(...c.places.map((x) => {
      const linked = x.id === p.kakaoPlaceId;
      const b = el('button', { type: 'button', class: linked ? 'button-secondary' : 'button-primary', disabled: linked, text: linked ? '연결됨' : '이 장소로 잇기', 'aria-label': `${x.name}${linked ? ' 연결됨' : ' 이 장소로 잇기'}` });
      b.addEventListener('click', () => change(() => api.link(p.id, x.id), `${x.name}(으)로 이었어요.`));
      const meta = [x.category, x.address, x.distanceM == null ? null : `우리 좌표에서 ${x.distanceM.toLocaleString()}m`, x.sameName ? '이름 같음' : null].filter(Boolean).join(' · ');
      return el('li', { class: 'find-item' },
        el('span', {}, el('strong', { text: x.name }), el('span', { class: 'find-addr', text: meta }),
          el('a', { class: 'small-link', href: `https://place.map.kakao.com/${x.id}`, target: '_blank', rel: 'noopener', text: '카카오맵에서 보기 (새 창)' })),
        b);
    }));
  } catch (e) {
    if (!handleAuthError(e)) $('candStatus').textContent = e.message;
  }
}

async function change(call, message) {
  $('dialogAlert').hidden = true;
  const buttons = [...$('placeDialog').querySelectorAll('button')];
  buttons.forEach((b) => { b.dataset.wasDisabled = b.disabled ? '1' : ''; b.disabled = true; });
  try {
    const p = await call();
    state.changed = true;
    fill(p);
    toast(message);
    return true;
  } catch (e) {
    if (handleAuthError(e)) { $('placeDialog').close(); return false; }
    showAlert(e.message, e.details);
    return false;
  } finally {
    buttons.forEach((b) => { if (b.isConnected) b.disabled = b.dataset.wasDisabled === '1'; });
  }
}

async function openDialog(p, opener) {
  state.opener = opener ?? null;
  state.changed = false;
  $('dialogAlert').hidden = true;
  fill(p);
  $('placeDialog').showModal();
  $('candQ').focus();
  loadCandidates();
}

async function init() {
  mountAccount($('account'));
  await auth.init();
  const tabs = [...document.querySelectorAll('[role="tab"]')];
  tabs.forEach((t, i) => {
    t.addEventListener('click', () => selectTab(t.dataset.tab));
    t.addEventListener('keydown', (e) => {
      const n = { ArrowRight: i + 1, ArrowLeft: i - 1, Home: 0, End: tabs.length - 1 }[e.key];
      if (n === undefined) return;
      e.preventDefault();
      selectTab(tabs[(n + tabs.length) % tabs.length].dataset.tab, true);
    });
  });
  $('filterForm').addEventListener('submit', (e) => {
    e.preventDefault();
    state.type = $('typeSelect').value;
    state.q = $('q').value.trim();
    loadList();
  });
  $('moreButton').addEventListener('click', () => { state.page += 1; loadList(true); });
  $('geocodeButton').addEventListener('click', () => startJob('geocode'));
  $('linkButton').addEventListener('click', () => startJob('link'));
  $('candForm').addEventListener('submit', (e) => { e.preventDefault(); loadCandidates($('candQ').value.trim()); });
  $('noPlaceButton').addEventListener('click', () => change(() => api.noPlace(state.current.id), '카카오맵에 없음으로 정했어요.'));
  $('unlinkButton').addEventListener('click', () => change(() => api.unlink(state.current.id), '카카오맵 연결을 풀었어요. 확인 필요로 옮겼어요.'));
  $('addressForm').addEventListener('submit', (e) => {
    e.preventDefault();
    change(() => api.address(state.current.id, $('roadAddress').value), '주소를 고치고 좌표를 다시 찾았어요.');
  });
  $('categoryForm').addEventListener('submit', (e) => {
    e.preventDefault();
    change(() => api.category(state.current.id, $('category').value), '분류를 저장했어요.');
  });
  $('doneButton').addEventListener('click', () => $('placeDialog').close());
  $('closeDialog').addEventListener('click', () => $('placeDialog').close());
  $('placeDialog').addEventListener('close', async () => {
    if (!state.changed) { state.opener?.focus?.(); return; }
    // 바꾼 뒤에는 목록을 새로 그린다. 그 가게가 아직 이 탭에 있으면 그 줄로, 다른 탭으로 옮겨 갔으면 목록으로 초점을 옮긴다
    const id = state.current?.id;
    await loadList();
    const again = document.querySelector(`#list li[data-id="${id}"] button`);
    (again ?? $('panel')).focus();
  });
  selectTab('REVIEW');
  loadJobs();
}

init();
