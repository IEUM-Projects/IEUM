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
    showAlert(e.message);
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
