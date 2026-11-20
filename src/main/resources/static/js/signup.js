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

/**
 * 비밀번호 규칙: 늘 보이는 두 줄(섞기·길이)은 맞으면 초록 체크.
 * 나머지 규칙은 어겼을 때만 빨간 줄로 나타난다 (처음엔 아래 도움말 한 줄로만 알려 줌).
 */
function renderRules() {
  const pw = form.password.value;
  const rules = checkPassword(pw, { email: form.email.value, nickname: form.nickname.value });
  const shown = rules.filter((rule) => rule.main || (pw.length > 0 && !rule.ok));
  rulesList.replaceChildren(...shown.map((rule) => {
    const li = document.createElement('li');
    li.className = rule.ok ? 'ok' : (touchedPassword || !rule.main ? 'bad' : '');
    const text = document.createElement('span');
    text.textContent = rule.label;
    li.append(text);
    if (rule.id === 'length' && pw.length > 0) {
      const count = document.createElement('span');
      count.className = 'rule-count';
      count.textContent = `지금 ${pw.length}자`;
      li.append(count);
    }
    // 화면 낭독기: 맞음/아직/틀림을 글로도 알려 줌
    const state = document.createElement('span');
    state.className = 'sr-only';
    state.textContent = rule.ok ? ' (맞아요)' : (li.className === 'bad' ? ' (고쳐 주세요)' : ' (아직)');
    li.append(state);
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
