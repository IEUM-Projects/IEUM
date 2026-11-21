// 로그인 화면: /login.html?next=/store.html?id=381
import * as auth from './auth.js';

const next = auth.safeNext(new URLSearchParams(location.search).get('next') ?? '/');
const form = document.getElementById('loginForm');
const alertBox = document.getElementById('formAlert');
const submitButton = document.getElementById('submitButton');

function showAlert(message) {
  alertBox.textContent = message;
  alertBox.hidden = !message;
}

// 회원가입 화면으로 가도 돌아갈 곳을 유지
document.getElementById('signupLink').href = `/signup.html?next=${encodeURIComponent(next)}`;
document.getElementById('findLink').href = `/forgot-password.html?next=${encodeURIComponent(next)}`;

// 비밀번호 찾기(AUTH-03)를 마치고 오면 안내
if (new URLSearchParams(location.search).get('reset') === '1') {
  const notice = document.getElementById('formNotice');
  notice.textContent = '비밀번호를 바꿨어요. 새 비밀번호로 로그인해 주세요.';
  notice.hidden = false;
}
document.getElementById('kakaoButton').addEventListener('click', () => auth.kakaoLogin(next));

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const email = form.email.value.trim();
  const password = form.password.value;
  if (!email || !password) {
    showAlert('이메일과 비밀번호를 입력해 주세요.');
    return;
  }
  submitButton.disabled = true;
  showAlert('');
  try {
    const user = await auth.login(email, password);
    location.replace(auth.afterLogin(user, next));
  } catch (e) {
    document.getElementById('formNotice').hidden = true;
    showAlert(e.code === 'ACCOUNT_LOCKED' ? `${e.message} 비밀번호가 기억나지 않으면 ‘비밀번호를 잊으셨나요?’로 새로 정할 수 있어요.` : e.message);
    form.password.value = '';
    form.password.focus();
  } finally {
    submitButton.disabled = false;
  }
});

// 이미 로그인한 상태로 들어오면 바로 돌려보낸다
auth.init({ agreeRedirect: false }).then((user) => {
  if (user) location.replace(auth.afterLogin(user, next));
});
