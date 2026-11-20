// 내 정보 화면: 닉네임 변경, 내가 쓴 이야기, 내 제보, 로그아웃, 회원 탈퇴 (로그인 필요)
// UI-04 2단계: 설정 앱처럼 묶음 목록으로 보여 준다
import * as auth from './auth.js';
import { renderRuleList } from './password-rules.js';
import { posts as postApi, when } from './community-common.js';

const NICKNAME_PATTERN = /^[가-힣a-zA-Z0-9]{2,12}$/;
const $ = (id) => document.getElementById(id);
const form = $('nicknameForm');

const CHEVRON = '<svg class="row-chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true" focusable="false"><path d="m9 6 6 6-6 6"/></svg>';
function addChevron(li) {
  li.insertAdjacentHTML('beforeend', CHEVRON);
  li.classList.add('has-link');
}

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
  renderPasswordRow(me);
}

// ---------------------------------------------------------------- 비밀번호 바꾸기 (AUTH-04)
// '바꾸기'를 누르면 줄이 펼쳐진다. 지금 비밀번호 + 새 비밀번호(가입과 같은 규칙) + 확인.
// 지금 비밀번호를 5번 틀리면 로그인처럼 10분 잠긴다 (서버). 바꾸면 이 기기는 그대로, 다른 기기는 로그아웃.

const passwordForm = $('passwordForm');
const passwordToggle = $('passwordToggle');
let myAccount = { email: '', nickname: '' };
let touchedNew = false;

function renderPasswordRow(me) {
  myAccount = { email: me.email ?? '', nickname: me.nickname };
  $('passwordRow').hidden = me.provider !== 'LOCAL';   // 카카오 회원은 비밀번호가 없음
}

function passwordFieldError(id, input, message) {
  $(id).textContent = message ?? '';
  input.setAttribute('aria-invalid', message ? 'true' : 'false');
}

function renderNewRules() {
  return renderRuleList($('passwordRules'), passwordForm.newPassword.value, { ...myAccount, touched: touchedNew });
}

function openPasswordForm(open) {
  passwordForm.hidden = !open;
  passwordToggle.hidden = open;
  passwordToggle.setAttribute('aria-expanded', String(open));
  if (open) {
    touchedNew = false;
    renderNewRules();
    passwordForm.currentPassword.focus();
  } else {
    passwordForm.reset();
    $('passwordAlert').hidden = true;
    ['currentError', 'newError', 'confirmNewError'].forEach((id) => { $(id).textContent = ''; });
    passwordForm.querySelectorAll('input').forEach((i) => i.removeAttribute('aria-invalid'));
    passwordToggle.focus();
  }
}

passwordToggle.addEventListener('click', () => { showNotice(''); openPasswordForm(true); });
$('passwordCancel').addEventListener('click', () => openPasswordForm(false));
passwordForm.addEventListener('keydown', (e) => { if (e.key === 'Escape') openPasswordForm(false); });
passwordForm.currentPassword.addEventListener('input', () => passwordFieldError('currentError', passwordForm.currentPassword, ''));
passwordForm.newPassword.addEventListener('input', () => { renderNewRules(); passwordFieldError('newError', passwordForm.newPassword, ''); });
passwordForm.newPasswordConfirm.addEventListener('input', () => passwordFieldError('confirmNewError', passwordForm.newPasswordConfirm, ''));

passwordForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  $('passwordAlert').hidden = true;
  touchedNew = true;
  const current = passwordForm.currentPassword.value;
  const next = passwordForm.newPassword.value;
  let ok = true;
  if (!current) { passwordFieldError('currentError', passwordForm.currentPassword, '지금 비밀번호를 입력해 주세요.'); ok = false; }
  else passwordFieldError('currentError', passwordForm.currentPassword, '');
  if (!renderNewRules()) { passwordFieldError('newError', passwordForm.newPassword, '비밀번호 규칙을 확인해 주세요.'); ok = false; }
  else if (next === current) { passwordFieldError('newError', passwordForm.newPassword, '지금 쓰는 비밀번호와 다른 비밀번호로 정해 주세요.'); ok = false; }
  else passwordFieldError('newError', passwordForm.newPassword, '');
  if (passwordForm.newPasswordConfirm.value !== next) { passwordFieldError('confirmNewError', passwordForm.newPasswordConfirm, '새 비밀번호가 서로 달라요.'); ok = false; }
  else passwordFieldError('confirmNewError', passwordForm.newPasswordConfirm, '');
  if (!ok) { passwordForm.querySelector('[aria-invalid="true"]').focus(); return; }

  $('passwordSave').disabled = true;
  try {
    await auth.changePassword(current, next);
    openPasswordForm(false);
    showNotice('비밀번호를 바꿨어요. 다른 기기에서는 로그아웃됐어요.');
    $('formAlert').scrollIntoView({ block: 'nearest' });
  } catch (e) {
    if (e.code === 'CURRENT_PASSWORD_MISMATCH') {
      passwordFieldError('currentError', passwordForm.currentPassword, e.message);
      passwordForm.currentPassword.select();
    } else if (e.code === 'WEAK_PASSWORD') {
      passwordFieldError('newError', passwordForm.newPassword, e.details.join(' ') || e.message);
      passwordForm.newPassword.focus();
    } else {
      // 잠김(ACCOUNT_LOCKED) 등은 위쪽 알림으로
      $('passwordAlert').textContent = e.code === 'ACCOUNT_LOCKED'
        ? '지금 비밀번호를 5번 틀려서 10분 동안 바꿀 수 없어요. 잠시 뒤에 다시 해 주세요.'
        : e.message;
      $('passwordAlert').hidden = false;
      $('passwordAlert').focus?.();
    }
  } finally {
    $('passwordSave').disabled = false;
  }
});

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
    setNicknameError(e.details?.[0] ?? e.message);   // 욕설·예약어는 까닭이 details 에 (AUTH-01)
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
  status.className = `row-badge ${STATUS_CLASS[r.status] ?? ''}`;
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
  li.append(store, top, type);
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
  if (r.storeVisible) addChevron(li);
  return li;
}

async function loadReports() {
  $('reports').hidden = false;
  $('reportsStatus').textContent = '불러오는 중...';
  try {
    const reports = await auth.fetchMyReports();
    $('reportList').replaceChildren(...reports.map(reportItem));
    $('reportsStatus').textContent = reports.length ? '' : '아직 보낸 제보가 없어요. 가게 화면의 \'영업 정보 제보\'로 보낼 수 있어요.';
    $('reportsStatus').hidden = !!reports.length;
  } catch (_) {
    $('reportsStatus').textContent = '제보 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
  if (location.hash === '#reports') $('reports').scrollIntoView();
}

// ---------------------------------------------------------------- 내가 쓴 이야기 (COM-01)
function myPostItem(p) {
  const li = document.createElement('li');
  li.className = 'report-item';
  const link = document.createElement('a');
  link.href = `/post.html?id=${p.id}`;
  link.className = 'report-store-link';
  link.textContent = p.title;
  const meta = document.createElement('p');
  meta.className = 'report-type';
  const cat = document.createElement('span');
  cat.className = 'row-category';
  cat.textContent = p.categoryLabel;
  const state = p.status === 'VISIBLE' ? '' : (p.status === 'TEMP_HIDDEN' ? ' · 임시로 가려짐' : ' · 관리자가 가림');
  meta.append(cat, ` ${when(p.createdAt)} · 공감 ${p.likeCount} · 댓글 ${p.commentCount}${state}${p.recommendStatusLabel ? ` · ${p.recommendStatusLabel}` : ''}`);
  li.append(link, meta);
  if (p.status !== 'VISIBLE') li.classList.add('is-hidden');
  addChevron(li);
  return li;
}

async function loadMyPosts() {
  $('myPosts').hidden = false;
  $('myPostsStatus').textContent = '불러오는 중...';
  try {
    const list = await postApi.mine();
    $('myPostList').replaceChildren(...list.map(myPostItem));
    $('myPostsStatus').textContent = list.length ? '' : '아직 쓴 이야기가 없어요.';
    $('myPostsStatus').hidden = !!list.length;
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
    $('sessionGroup').hidden = false;
    loadMyPosts();
    loadReports();
    if (location.hash === '#withdraw') $('withdraw').scrollIntoView();
  } catch (_) {
    location.replace(auth.loginUrl('/mypage.html'));
  }
}

init();
