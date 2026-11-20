// 비밀번호 찾기 (AUTH-03): 이메일 → 메일로 온 6자리 번호 → 새 비밀번호 → 로그인 화면으로
// 가입하지 않은 이메일이어도 서버는 '보냈어요'라고 답한다 (가입 여부를 알려 주지 않음). 그래서 화면도 '가입된 이메일이면'이라고 말한다.
import * as auth from './auth.js';
import { renderRuleList } from './password-rules.js';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const next = auth.safeNext(new URLSearchParams(location.search).get('next') ?? '/');
const loginUrl = `/login.html?next=${encodeURIComponent(next)}`;

const findForm = document.getElementById('findForm');
const resetForm = document.getElementById('resetForm');
const alertBox = document.getElementById('formAlert');
const emailInput = document.getElementById('email');
const emailOk = document.getElementById('emailOk');
const sendCodeButton = document.getElementById('sendCode');
const codeBox = document.getElementById('codeBox');
const codeInput = document.getElementById('emailCode');
const confirmCodeButton = document.getElementById('confirmCode');
const resendButton = document.getElementById('resendCode');
const codeTimer = document.getElementById('codeTimer');
const codeError = document.getElementById('codeError');
const rulesList = document.getElementById('passwordRules');
const submitButton = document.getElementById('submitButton');

let sentTo = null;        // 번호를 보낸 이메일 (소문자)
let verifiedEmail = null; // 번호를 맞힌 이메일
let codeDeadline = 0;
let resendAt = 0;
let timer = null;
let touchedPassword = false;

document.getElementById('loginLink').href = loginUrl;
document.getElementById('backLink').href = loginUrl;

function showAlert(message) {
  alertBox.textContent = message ?? '';
  alertBox.hidden = !message;
}

function setError(id, input, message) {
  document.getElementById(id).textContent = message ?? '';
  input.setAttribute('aria-invalid', message ? 'true' : 'false');
}

async function postJson(url, body) {
  const res = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const data = res.status === 204 ? {} : await res.json().catch(() => ({}));
  if (!res.ok) throw Object.assign(new Error(data.message ?? '잠시 후 다시 시도해 주세요.'), { code: data.code, details: data.details ?? [] });
  return data;
}

// ---------------------------------------------------------------- 1단계: 번호 받기·확인
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
  const email = emailInput.value.trim();
  emailOk.textContent = '';
  showAlert('');
  if (!EMAIL_PATTERN.test(email)) { setError('emailError', emailInput, '이메일 형식이 올바르지 않아요.'); emailInput.focus(); return; }
  setError('emailError', emailInput, '');
  sendCodeButton.disabled = true;
  resendButton.disabled = true;
  try {
    const sent = await postJson('/api/auth/password-reset', { email });
    sentTo = sent.email;
    codeDeadline = Date.now() + sent.expiresIn * 1000;
    resendAt = Date.now() + sent.resendAfter * 1000;
    codeBox.hidden = false;
    sendCodeButton.hidden = true;   // 다시 받기는 번호 칸 아래에서만
    codeError.textContent = '';
    codeInput.value = '';
    codeInput.removeAttribute('aria-invalid');
    emailOk.textContent = `가입된 이메일이면 ${sent.email}로 인증번호를 보냈어요.`;
    if (!timer) timer = setInterval(tick, 1000);
    tick();
    codeInput.focus();
  } catch (e) {
    setError('emailError', emailInput, e.message);
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
    const ok = await postJson('/api/auth/password-reset/confirm', { email: sentTo, code });
    verifiedEmail = ok.email;
    clearInterval(timer); timer = null;
    findForm.hidden = true;
    resetForm.hidden = false;
    document.getElementById('lead').textContent = '새로 쓸 비밀번호를 정해 주세요.';
    document.getElementById('verifiedNote').textContent = `${ok.email} 인증을 마쳤어요. ${Math.round(ok.validFor / 60)}분 안에 새 비밀번호를 정해 주세요.`;
    renderRules();
    resetForm.password.focus();
  } catch (e) {
    codeError.textContent = e.details?.[0] ?? e.message;
    codeInput.setAttribute('aria-invalid', 'true');
    codeInput.select();
  } finally {
    confirmCodeButton.disabled = false;
  }
}

/** 처음(이메일 입력)으로 돌아가기 */
function restart(message) {
  sentTo = null;
  verifiedEmail = null;
  clearInterval(timer); timer = null;
  resetForm.hidden = true;
  resetForm.reset();
  findForm.hidden = false;
  codeBox.hidden = true;
  sendCodeButton.hidden = false;
  emailOk.textContent = '';
  document.getElementById('lead').textContent = '가입한 이메일로 인증번호를 보내 드려요.';
  showAlert(message);
  sendCodeButton.focus();
}

sendCodeButton.addEventListener('click', sendCode);
resendButton.addEventListener('click', sendCode);
confirmCodeButton.addEventListener('click', confirmCode);
findForm.addEventListener('submit', (e) => { e.preventDefault(); codeBox.hidden ? sendCode() : confirmCode(); });
emailInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); sendCode(); } });
codeInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); confirmCode(); } });
codeInput.addEventListener('input', () => {
  codeError.textContent = '';
  codeInput.removeAttribute('aria-invalid');
  if (/^\d{6}$/.test(codeInput.value)) confirmCode();   // 6자리를 다 치면 바로 확인
});
emailInput.addEventListener('input', () => {
  setError('emailError', emailInput, '');
  if (!codeBox.hidden) {   // 번호를 보낸 뒤 주소를 고치면 처음부터
    sentTo = null;
    codeBox.hidden = true;
    sendCodeButton.hidden = false;
    clearInterval(timer); timer = null;
  }
  emailOk.textContent = '';
});

// ---------------------------------------------------------------- 2단계: 새 비밀번호
// 규칙 표시는 가입 화면과 같다: 늘 보이는 두 줄(섞기·길이) + 어긴 규칙만 빨간 줄. 닉네임 규칙은 서버가 본다
function renderRules() {
  return renderRuleList(rulesList, resetForm.password.value, { email: verifiedEmail ?? '', touched: touchedPassword });
}

resetForm.password.addEventListener('input', () => { renderRules(); setError('passwordError', resetForm.password, ''); });
resetForm.passwordConfirm.addEventListener('input', () => setError('confirmError', resetForm.passwordConfirm, ''));

resetForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  showAlert('');
  touchedPassword = true;
  const pw = resetForm.password.value;
  let ok = true;
  if (!renderRules()) { setError('passwordError', resetForm.password, '비밀번호 규칙을 확인해 주세요.'); ok = false; }
  else setError('passwordError', resetForm.password, '');
  if (resetForm.passwordConfirm.value !== pw) { setError('confirmError', resetForm.passwordConfirm, '비밀번호가 서로 달라요.'); ok = false; }
  else setError('confirmError', resetForm.passwordConfirm, '');
  if (!ok) { resetForm.querySelector('[aria-invalid="true"]').focus(); return; }

  submitButton.disabled = true;
  try {
    await postJson('/api/auth/password-reset/complete', { email: verifiedEmail, password: pw });
    location.replace(`${loginUrl}&reset=1`);
  } catch (e) {
    if (e.code === 'WEAK_PASSWORD') {
      setError('passwordError', resetForm.password, e.details.join(' ') || e.message);
      resetForm.password.focus();
    } else if (e.code === 'RESET_NOT_VERIFIED') {
      restart(e.message);
    } else {
      showAlert(e.message);
    }
  } finally {
    submitButton.disabled = false;
  }
});
