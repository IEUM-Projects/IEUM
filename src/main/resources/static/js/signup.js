// 회원가입 화면: 비밀번호 규칙을 입력하는 동안 바로 보여 주고, 가입하면 자동 로그인
import * as auth from './auth.js';
import { checkPassword } from './password-rules.js';

const NICKNAME_PATTERN = /^[가-힣a-zA-Z0-9]{2,12}$/;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const next = auth.safeNext(new URLSearchParams(location.search).get('next') ?? '/');
const form = document.getElementById('signupForm');
const alertBox = document.getElementById('formAlert');
const rulesList = document.getElementById('passwordRules');
const submitButton = document.getElementById('submitButton');
let touchedPassword = false;

document.getElementById('loginLink').href = `/login.html?next=${encodeURIComponent(next)}`;
document.getElementById('kakaoButton').addEventListener('click', () => auth.kakaoLogin(next));

function setError(field, message) {
  document.getElementById(`${field}Error`).textContent = message ?? '';
  const input = field === 'confirm' ? form.passwordConfirm : form[field];
  input.setAttribute('aria-invalid', message ? 'true' : 'false');
}

function showAlert(message) {
  alertBox.textContent = message ?? '';
  alertBox.hidden = !message;
}

function renderRules() {
  const rules = checkPassword(form.password.value, { email: form.email.value, nickname: form.nickname.value });
  rulesList.replaceChildren(...rules.map((rule) => {
    const li = document.createElement('li');
    li.textContent = rule.label;
    li.className = rule.ok ? 'ok' : (touchedPassword ? 'bad' : '');
    return li;
  }));
  return rules.every((rule) => rule.ok);
}

function validate() {
  const email = form.email.value.trim();
  const nickname = form.nickname.value.trim();
  let ok = true;

  if (!EMAIL_PATTERN.test(email)) { setError('email', '이메일 형식이 올바르지 않아요.'); ok = false; }
  else setError('email', '');

  if (!NICKNAME_PATTERN.test(nickname)) { setError('nickname', '닉네임은 한글, 영문, 숫자로 2~12자예요.'); ok = false; }
  else setError('nickname', '');

  touchedPassword = true;
  if (!renderRules()) { setError('password', '비밀번호 규칙을 확인해 주세요.'); ok = false; }
  else setError('password', '');

  if (form.passwordConfirm.value !== form.password.value) { setError('confirm', '비밀번호가 서로 달라요.'); ok = false; }
  else setError('confirm', '');

  return ok;
}

form.password.addEventListener('input', () => { renderRules(); setError('password', ''); });
form.email.addEventListener('input', () => { renderRules(); setError('email', ''); });
form.nickname.addEventListener('input', () => { renderRules(); setError('nickname', ''); });
form.passwordConfirm.addEventListener('input', () => setError('confirm', ''));

// ---------------------------------------------------------------- 약관 동의 (POL-01)
const agreeAll = document.getElementById('agreeAll');
const agreeBoxes = [...document.querySelectorAll('[data-agree]')];

function syncAgreeAll() {
  const checked = agreeBoxes.filter((b) => b.checked).length;
  agreeAll.checked = checked === agreeBoxes.length;
  agreeAll.indeterminate = checked > 0 && checked < agreeBoxes.length;
}

agreeAll.addEventListener('change', () => {
  agreeBoxes.forEach((b) => { b.checked = agreeAll.checked; });
  document.getElementById('agreeError').textContent = '';
});
agreeBoxes.forEach((b) => b.addEventListener('change', () => {
  syncAgreeAll();
  if (agreeBoxes.every((x) => x.checked)) document.getElementById('agreeError').textContent = '';
}));

function validateAgreements() {
  const missing = agreeBoxes.find((b) => !b.checked);
  document.getElementById('agreeError').textContent = missing ? '필수 항목에 모두 동의해 주세요.' : '';
  agreeBoxes.forEach((b) => b.setAttribute('aria-invalid', b.checked ? 'false' : 'true'));
  return !missing;
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  showAlert('');
  const formOk = validate();
  const agreeOk = validateAgreements();
  if (!formOk || !agreeOk) {
    (form.querySelector('[aria-invalid="true"]') ?? agreeBoxes.find((b) => !b.checked))?.focus();
    return;
  }

  submitButton.disabled = true;
  try {
    await auth.signupAndLogin({
      email: form.email.value.trim(),
      password: form.password.value,
      nickname: form.nickname.value.trim(),
      agreeTerms: true,
      agreePrivacy: true,
      over14: true,
    });
    location.replace(next);
  } catch (e) {
    // 서버가 알려 준 오류를 해당 칸 아래에 표시
    if (e.code === 'SIGNED_UP_LOGIN_FAILED') location.replace(`/login.html?next=${encodeURIComponent(next)}`);
    else if (e.code === 'DUPLICATE_EMAIL') setError('email', e.message);
    else if (e.code === 'DUPLICATE_NICKNAME') setError('nickname', e.message);
    else if (e.code === 'WEAK_PASSWORD') setError('password', e.details.join(' '));
    else if (e.code === 'INVALID_INPUT' && e.details.some((d) => d.includes('동의') || d.includes('14세'))) {
      document.getElementById('agreeError').textContent = e.details.join(' ');
    } else if (e.code === 'INVALID_INPUT') showAlert(e.details.length ? e.details.join(' ') : e.message);
    else showAlert(e.message);
  } finally {
    submitButton.disabled = false;
  }
});

renderRules();
auth.init({ agreeRedirect: false }).then((user) => {
  if (user) location.replace(auth.afterLogin(user, next));
});
