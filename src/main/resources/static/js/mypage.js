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

async function init() {
  const user = await auth.init();
  if (!user) {
    location.replace(auth.loginUrl('/mypage.html'));
    return;
  }
  try {
    render(await auth.fetchMe());
    $('card').hidden = false;
  } catch (_) {
    location.replace(auth.loginUrl('/mypage.html'));
  }
}

init();
