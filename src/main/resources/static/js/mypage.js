// 내 정보 화면: 닉네임 변경, 로그아웃 (로그인 필요)
import * as auth from './auth.js';
import { posts as postApi, when } from './community-common.js';

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
  $('agreedAt').textContent = me.needsAgreement || !me.agreedAt
    ? '아직 동의하지 않았어요'
    : `${me.agreedAt.slice(0, 10).replaceAll('-', '.')} 동의`;
  renderWithdraw(me);
}

// ---------------------------------------------------------------- 회원 탈퇴 (POL-01)

let provider = 'LOCAL';

function renderWithdraw(me) {
  provider = me.provider;
  const kakao = me.provider !== 'LOCAL';
  $('passwordField').hidden = kakao;
  $('confirmField').hidden = !kakao;
  $('kakaoNote').hidden = !kakao;
  $('withdraw').hidden = me.role === 'ADMIN';   // 관리자는 탈퇴할 수 없음 (서버도 막음)
}

function withdrawError(message) {
  $('withdrawError').textContent = message ?? '';
  const input = provider === 'LOCAL' ? $('withdrawPassword') : $('withdrawConfirm');
  input.setAttribute('aria-invalid', message ? 'true' : 'false');
  if (message) input.focus();
}

$('withdrawForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  $('withdrawAlert').hidden = true;
  const local = provider === 'LOCAL';
  const password = $('withdrawPassword').value;
  const confirm = $('withdrawConfirm').value.trim();
  if (local && !password) { withdrawError('비밀번호를 입력해 주세요.'); return; }
  if (!local && confirm !== '탈퇴') { withdrawError("확인을 위해 '탈퇴'를 입력해 주세요."); return; }
  withdrawError('');
  $('withdrawButton').disabled = true;
  try {
    await auth.withdraw(local ? { password } : { confirm });
    location.replace('/?withdrawn=1');
  } catch (e) {
    if (e.code === 'WITHDRAW_PASSWORD_MISMATCH' || e.code === 'WITHDRAW_CONFIRM_REQUIRED') withdrawError(e.message);
    else {
      $('withdrawAlert').textContent = e.message;
      $('withdrawAlert').hidden = false;
      $('withdrawAlert').focus();
    }
  } finally {
    $('withdrawButton').disabled = false;
  }
});

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

// ---------------------------------------------------------------- 내가 쓴 이야기 (COM-01)
function myPostItem(p) {
  const li = document.createElement('li');
  li.className = 'report-item';
  const top = document.createElement('p');
  top.className = 'report-top';
  const badge = document.createElement('span');
  badge.className = 'report-status report-received';
  badge.textContent = p.categoryLabel;
  const link = document.createElement('a');
  link.href = `/post.html?id=${p.id}`;
  link.className = 'report-store-link';
  link.textContent = p.title;
  top.append(badge, link);
  const meta = document.createElement('p');
  meta.className = 'report-type';
  const state = p.status === 'VISIBLE' ? '' : (p.status === 'TEMP_HIDDEN' ? ' · 임시로 가려짐' : ' · 관리자가 가림');
  meta.textContent = `${when(p.createdAt)} · 공감 ${p.likeCount} · 댓글 ${p.commentCount}${state}${p.recommendStatusLabel ? ` · ${p.recommendStatusLabel}` : ''}`;
  li.append(top, meta);
  return li;
}

async function loadMyPosts() {
  $('myPosts').hidden = false;
  $('myPostsStatus').textContent = '불러오는 중...';
  try {
    const list = await postApi.mine();
    $('myPostList').replaceChildren(...list.map(myPostItem));
    $('myPostsStatus').textContent = list.length ? '' : '아직 쓴 이야기가 없어요.';
  } catch (_) {
    $('myPostsStatus').textContent = '글 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
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
    loadMyPosts();
    loadReports();
    if (location.hash === '#withdraw') $('withdraw').scrollIntoView();
  } catch (_) {
    location.replace(auth.loginUrl('/mypage.html'));
  }
}

init();
