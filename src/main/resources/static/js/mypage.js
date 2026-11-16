// 내 정보 화면: 닉네임 변경, 로그아웃 (로그인 필요)
import * as auth from './auth.js';

const NICKNAME_PATTERN = /^[가-힣a-zA-Z0-9]{2,12}$/;
const $ = (id) => document.getElementById(id);
const form = $('nicknameForm');

function setNicknameError(message) {
  $('nicknameError').textContent = message ?? '';
  form.nickname.setAttribute('aria-invalid', message ? 'true' : 'false');
}

function showNotice(message) {
  $('formAlert').textContent = message ?? '';
  $('formAlert').className = 'form-alert success';
  $('formAlert').hidden = !message;
}

function render(me) {
  $('welcome').textContent = `${me.nickname}님, 반가워요.`;
  $('provider').textContent = me.provider === 'KAKAO' ? '카카오' : '이메일';
  $('email').textContent = me.email ?? '없음 (카카오 로그인)';
  $('createdAt').textContent = me.createdAt ? me.createdAt.slice(0, 10).replaceAll('-', '.') : '';
  form.nickname.value = me.nickname;
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  showNotice('');
  const nickname = form.nickname.value.trim();
  if (!NICKNAME_PATTERN.test(nickname)) {
    setNicknameError('닉네임은 한글, 영문, 숫자로 2~12자예요.');
    return;
  }
  setNicknameError('');
  $('saveButton').disabled = true;
  try {
    const me = await auth.updateNickname(nickname);
    render(me);
    showNotice('닉네임을 바꿨어요.');
  } catch (e) {
    setNicknameError(e.message);
  } finally {
    $('saveButton').disabled = false;
  }
});

$('logoutButton').addEventListener('click', async () => {
  await auth.logout();
  location.replace('/');
});

// ---------------------------------------------------------------- 내 제보

const STATUS_CLASS = { RECEIVED: 'report-received', APPLIED: 'report-applied', REJECTED: 'report-rejected' };

function reportItem(r) {
  const li = document.createElement('li');
  li.className = 'report-item';
  const top = document.createElement('div');
  top.className = 'report-top';
  const status = document.createElement('span');
  status.className = `report-status ${STATUS_CLASS[r.status] ?? ''}`;
  status.textContent = r.statusLabel;
  const date = document.createElement('span');
  date.className = 'report-date';
  date.textContent = r.createdAt ? r.createdAt.slice(0, 10).replaceAll('-', '.') : '';
  top.append(status, date);
  // 폐업 처리 등으로 지도에서 내려간 가게는 화면이 없으므로 링크 대신 이름만
  const store = document.createElement(r.storeVisible ? 'a' : 'strong');
  if (r.storeVisible) store.href = `/store.html?id=${r.storeId}`;
  store.className = r.storeVisible ? 'report-store-link' : 'report-store-name';
  store.textContent = r.storeVisible ? r.storeName : `${r.storeName} (지도에서 내려간 가게)`;
  const type = document.createElement('p');
  type.className = 'report-type';
  type.textContent = r.typeLabel;
  li.append(top, store, type);
  if (r.content) {
    const content = document.createElement('p');
    content.className = 'report-content';
    content.textContent = r.content;
    li.append(content);
  }
  if (r.adminNote) {
    const note = document.createElement('p');
    note.className = 'report-note';
    note.textContent = `처리 메모: ${r.adminNote}`;
    if (r.reviewedAt) {
      const when = document.createElement('span');
      when.className = 'report-note-date';
      when.textContent = ` (${r.reviewedAt.slice(0, 10).replaceAll('-', '.')} 처리)`;
      note.append(when);
    }
    li.append(note);
  }
  return li;
}

async function loadReports() {
  $('reports').hidden = false;
  $('reportsStatus').textContent = '불러오는 중...';
  try {
    const reports = await auth.fetchMyReports();
    $('reportList').replaceChildren(...reports.map(reportItem));
    $('reportsStatus').textContent = reports.length ? '' : '아직 보낸 제보가 없어요. 가게 화면의 \'영업 정보 제보\'로 보낼 수 있어요.';
  } catch (_) {
    $('reportsStatus').textContent = '제보 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
  if (location.hash === '#reports') $('reports').scrollIntoView();
}

async function init() {
  const user = await auth.init();
  if (!user) {
    location.replace(auth.loginUrl('/mypage.html'));
    return;
  }
  try {
    render(await auth.fetchMe());
    $('card').hidden = false;
    loadReports();
  } catch (_) {
    location.replace(auth.loginUrl('/mypage.html'));
  }
}

init();
