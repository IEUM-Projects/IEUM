// 회원가입 화면: 비밀번호 규칙을 입력하는 동안 바로 보여 주고, 가입하면 자동 로그인
import * as auth from './auth.js';
import { renderRuleList } from './password-rules.js';

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

/**
 * 비밀번호 규칙: 늘 보이는 두 줄(섞기·길이)은 맞으면 초록 체크.
 * 나머지 규칙은 어겼을 때만 빨간 줄로 나타난다 (처음엔 아래 도움말 한 줄로만 알려 줌).
 */
function renderRules() {
  return renderRuleList(rulesList, form.password.value,
    { email: form.email.value, nickname: form.nickname.value, touched: touchedPassword });
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
form.email.addEventListener('input', () => {
  renderRules();
  setError('email', '');
  if (form.email.readOnly) return;
  if (!codeBox.hidden) resetVerification();   // 번호를 보낸 뒤 주소를 고치면 새로 받아야 함
  else emailOk.textContent = '';
});
// ---------------------------------------------------------------- 이메일 인증 (AUTH-02)
// '인증번호 받기' → 메일로 온 6자리 입력 → '확인'이 맞으면 이메일 칸을 잠근다. 30분 안에 가입해야 한다.
// 서버 설정이 꺼져 있으면(개발·시험) 버튼을 감추고 전처럼 가입한다.
const sendCodeButton = document.getElementById('sendCode');
const codeBox = document.getElementById('codeBox');
const codeInput = document.getElementById('emailCode');
const confirmCodeButton = document.getElementById('confirmCode');
const resendButton = document.getElementById('resendCode');
const codeTimer = document.getElementById('codeTimer');
const codeError = document.getElementById('codeError');
const emailOk = document.getElementById('emailOk');
const changeEmailButton = document.getElementById('changeEmail');
let verificationRequired = false;
let verifiedEmail = null;
let codeDeadline = 0;
let resendAt = 0;
let timer = null;

async function postJson(url, body) {
  const res = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw Object.assign(new Error(data.message ?? '잠시 후 다시 시도해 주세요.'), { code: data.code, details: data.details ?? [] });
  return data;
}

function tick() {
  const now = Date.now();
  const left = Math.max(0, Math.round((codeDeadline - now) / 1000));
  codeTimer.textContent = left > 0 ? `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')} 남음.` : '시간이 지났어요. 새 번호를 받아 주세요.';
  const wait = Math.max(0, Math.ceil((resendAt - now) / 1000));
  resendButton.disabled = wait > 0;
  resendButton.textContent = wait > 0 ? `다시 받기 (${wait}초)` : '다시 받기';
  if (left === 0 && wait === 0) { clearInterval(timer); timer = null; }
}

async function sendCode() {
  const email = form.email.value.trim();
  emailOk.textContent = '';
  if (!EMAIL_PATTERN.test(email)) { setError('email', '이메일 형식이 올바르지 않아요.'); form.email.focus(); return; }
  setError('email', '');
  sendCodeButton.disabled = true;
  resendButton.disabled = true;
  try {
    const sent = await postJson('/api/auth/email-verification', { email });
    codeDeadline = Date.now() + sent.expiresIn * 1000;
    resendAt = Date.now() + sent.resendAfter * 1000;
    codeBox.hidden = false;
    codeError.textContent = '';
    codeInput.value = '';
    codeInput.removeAttribute('aria-invalid');
    sendCodeButton.hidden = true;   // 다시 받기는 번호 칸 아래에서만 (1분 기다림을 보여 주려고)
    emailOk.textContent = `${sent.email}로 인증번호를 보냈어요.`;
    if (!timer) timer = setInterval(tick, 1000);
    tick();
    codeInput.focus();
  } catch (e) {
    setError('email', e.message);
  } finally {
    sendCodeButton.disabled = false;
    if (timer) tick(); else resendButton.disabled = false;
  }
}

async function confirmCode() {
  const code = codeInput.value.replace(/\s/g, '');
  if (!/^\d{6}$/.test(code)) {
    codeError.textContent = '메일로 받은 숫자 6자리를 입력해 주세요.';
    codeInput.setAttribute('aria-invalid', 'true');
    codeInput.focus();
    return;
  }
  confirmCodeButton.disabled = true;
  try {
    const ok = await postJson('/api/auth/email-verification/confirm', { email: form.email.value.trim(), code });
    verifiedEmail = ok.email;
    clearInterval(timer); timer = null;
    codeBox.hidden = true;
    form.email.readOnly = true;
    sendCodeButton.hidden = true;
    changeEmailButton.hidden = false;
    setError('email', '');
    emailOk.textContent = '이메일을 인증했어요. 30분 안에 가입을 마쳐 주세요.';
    form.nickname.focus();
  } catch (e) {
    codeError.textContent = e.details?.[0] ?? e.message;
    codeInput.setAttribute('aria-invalid', 'true');
    codeInput.select();
  } finally {
    confirmCodeButton.disabled = false;
  }
}

function resetVerification({ keepMessage = false } = {}) {
  verifiedEmail = null;
  form.email.readOnly = false;
  sendCodeButton.hidden = !verificationRequired;
  sendCodeButton.textContent = '인증번호 받기';
  changeEmailButton.hidden = true;
  codeBox.hidden = true;
  clearInterval(timer); timer = null;
  if (!keepMessage) emailOk.textContent = '';
}

sendCodeButton.addEventListener('click', sendCode);
resendButton.addEventListener('click', sendCode);
confirmCodeButton.addEventListener('click', confirmCode);
codeInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); confirmCode(); } });
codeInput.addEventListener('input', () => {
  codeError.textContent = '';
  codeInput.removeAttribute('aria-invalid');
  if (/^\d{6}$/.test(codeInput.value)) confirmCode();   // 6자리를 다 치면 바로 확인
});
changeEmailButton.addEventListener('click', () => { resetVerification(); form.email.focus(); });

fetch('/api/auth/signup-options').then((r) => r.json()).then((o) => {
  verificationRequired = !!o.emailVerification;
  sendCodeButton.hidden = !verificationRequired;
}).catch(() => { /* 알 수 없으면 버튼 없이: 가입할 때 서버가 알려 준다 */ });

// ---------------------------------------------------------------- 닉네임 중복 확인 (AUTH-01)
// 확인한 값과 결과를 기억해 두고, 글자를 바꾸면 다시 확인해야 한다. 가입할 때도 서버가 한 번 더 확인한다.
const nicknameOk = document.getElementById('nicknameOk');
const nicknameCheckButton = document.getElementById('nicknameCheck');
let checkedNickname = null;   // { value, available }

function showNicknameOk(message) {
  nicknameOk.textContent = message ?? '';
}

async function checkNickname() {
  const nickname = form.nickname.value.trim();
  showNicknameOk('');
  if (!NICKNAME_PATTERN.test(nickname)) {
    setError('nickname', '닉네임은 한글, 영문, 숫자로 2~12자예요.');
    return false;
  }
  if (checkedNickname?.value === nickname) {
    if (checkedNickname.available) showNicknameOk('쓸 수 있는 닉네임이에요.');
    else setError('nickname', '이미 사용 중인 닉네임이에요.');
    return checkedNickname.available;
  }
  nicknameCheckButton.disabled = true;
  try {
    const res = await fetch(`/api/auth/nickname-check?${new URLSearchParams({ nickname })}`);
    if (!res.ok) throw new Error();
    const body = await res.json();
    if (form.nickname.value.trim() !== nickname) return false;   // 확인하는 사이에 글자를 바꿈
    checkedNickname = { value: nickname, available: body.available };
    if (body.available) { setError('nickname', ''); showNicknameOk(body.message); }
    else setError('nickname', body.message);
    return body.available;
  } catch (_) {
    setError('nickname', '중복 확인을 하지 못했어요. 잠시 후 다시 눌러 주세요.');
    return null;   // 알 수 없음: 가입은 막지 않고 서버 확인에 맡김
  } finally {
    nicknameCheckButton.disabled = false;
  }
}

nicknameCheckButton.addEventListener('click', checkNickname);
form.nickname.addEventListener('input', () => {
  renderRules();
  setError('nickname', '');
  showNicknameOk('');
  checkedNickname = null;
});
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
  // 이메일 인증 (AUTH-02)
  if (verificationRequired && verifiedEmail !== form.email.value.trim().toLowerCase()) {
    setError('email', '이메일 인증을 먼저 해 주세요.');
    (codeBox.hidden ? sendCodeButton : codeInput).focus();
    return;
  }
  // 다른 칸이 모두 맞을 때, 중복 확인을 누르지 않았으면 가입 전에 대신 확인한다
  if (checkedNickname?.value !== form.nickname.value.trim()) {
    if (await checkNickname() === false) {
      form.nickname.focus();
      return;
    }
  } else if (!checkedNickname.available) {
    setError('nickname', '이미 사용 중인 닉네임이에요.');
    form.nickname.focus();
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
    else if (e.code === 'EMAIL_NOT_VERIFIED') {
      // 30분이 지났거나 서버 설정이 켜져 있는데 화면이 몰랐던 경우
      verificationRequired = true;
      resetVerification();
      setError('email', '이메일 인증을 다시 해 주세요. 인증한 지 30분이 지났을 수 있어요.');
      sendCodeButton.focus();
    }
    else if (e.code === 'NICKNAME_NOT_ALLOWED') { showNicknameOk(''); checkedNickname = { value: form.nickname.value.trim(), available: false }; setError('nickname', e.details[0] ?? e.message); }
    else if (e.code === 'DUPLICATE_NICKNAME') { showNicknameOk(''); checkedNickname = { value: form.nickname.value.trim(), available: false }; setError('nickname', e.message); }
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
