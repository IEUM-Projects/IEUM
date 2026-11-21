// 관리자 작업 기록 (ADM-03): 분류·관리자로 좁히기, 대상 하나의 기록(?targetType=USER&targetId=5), 더 보기
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { el, request } from './community-common.js';
import { actionRow } from './admin-actions.js';

const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
const PAGE = '/admin-logs.html';
const state = { page: 0, count: 0, target: null, seq: 0 };
const TYPE_LABELS = { STORE: '가게', POST: '글', COMMENT: '댓글', USER: '회원', MARKET: '시장', NOTICE: '공지', FACILITY: '시장 시설' };

function showGate(message) {
  $('gate').textContent = message;
  $('gate').hidden = false;
  $('workspace').hidden = true;
}

function query(page) {
  const q = new URLSearchParams({ page: String(page) });
  if ($('category').value) q.set('category', $('category').value);
  if ($('adminId').value) q.set('adminId', $('adminId').value);
  if (state.target) { q.set('targetType', state.target.type); q.set('targetId', state.target.id); }
  return `/api/admin/actions?${q}`;
}

let optionsReady = false;
function fillOptions(data) {
  if (optionsReady) return;
  optionsReady = true;
  $('category').append(...Object.entries(data.categories).sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => el('option', { value: k, text: v })));
  $('adminId').append(...data.admins.map((a) => el('option', { value: String(a.id), text: a.nickname })));
  const c = params.get('category');
  if (c && [...$('category').options].some((o) => o.value === c)) $('category').value = c;
}

async function load({ append = false } = {}) {
  const page = append ? state.page + 1 : 0;
  const seq = ++state.seq;   // 고르기를 빨리 바꾸면 늦게 온 옛 응답이 새 목록을 덮지 않게
  $('listStatus').textContent = '불러오는 중...';
  $('more').disabled = true;
  try {
    const data = await request(query(page));
    if (seq !== state.seq) return;
    fillOptions(data);
    state.page = page;
    const rows = data.items.map(actionRow);
    if (append) $('list').append(...rows); else $('list').replaceChildren(...rows);
    state.count = append ? state.count + rows.length : rows.length;
    $('listStatus').textContent = state.count ? `${state.count.toLocaleString()}줄${data.hasNext ? ' (더 있어요)' : ''}` : '이 조건에 맞는 기록이 없어요.';
    $('more').hidden = !data.hasNext;
    if (append && rows[0]) { rows[0].tabIndex = -1; rows[0].focus(); }   // 새로 붙은 첫 줄로
  } catch (e) {
    if (e.status === 401) { location.replace(auth.loginUrl(PAGE)); return; }
    if (e.status === 403) { showGate('관리자만 볼 수 있는 화면이에요. 관리자 권한을 받았다면 로그아웃했다가 다시 로그인해 주세요.'); return; }
    $('listStatus').textContent = e.message || '기록을 불러오지 못했어요.';
  } finally {
    $('more').disabled = false;
  }
}

async function init() {
  mountAccount($('account'), { onLogout: () => location.replace('/') });
  const user = await auth.init();
  if (!user) { location.replace(auth.loginUrl(PAGE + location.search)); return; }
  if (user.role !== 'ADMIN') { showGate('관리자만 볼 수 있는 화면이에요.'); return; }
  const tt = params.get('targetType');
  const tid = params.get('targetId');
  if (tt && TYPE_LABELS[tt] && tid && /^[\w-]{1,40}$/.test(tid)) {
    state.target = { type: tt, id: tid };
    $('targetText').textContent = `${TYPE_LABELS[tt]} #${tid}에 한 작업만 보고 있어요.`;
    $('targetNote').hidden = false;
  }
  $('gate').hidden = true;
  $('workspace').hidden = false;
  $('category').addEventListener('change', () => load());
  $('adminId').addEventListener('change', () => load());
  $('filterForm').addEventListener('submit', (e) => { e.preventDefault(); load(); });
  $('more').addEventListener('click', () => load({ append: true }));
  await load();
}

init();
