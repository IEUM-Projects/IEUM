// 약관 동의 화면 (POL-01): 카카오로 처음 가입했거나, 약관·처리방침이 바뀐 뒤 처음 로그인한 회원
import * as auth from './auth.js';
import { api } from './api.js';

const $ = (id) => document.getElementById(id);
const next = auth.safeNext(new URLSearchParams(location.search).get('next') ?? '/');
const boxes = [...document.querySelectorAll('[data-agree]')];

function sync() {
  const checked = boxes.filter((b) => b.checked).length;
  $('agreeAll').checked = checked === boxes.length;
  $('agreeAll').indeterminate = checked > 0 && checked < boxes.length;
  if (checked === boxes.length) $('agreeError').textContent = '';
}

$('agreeAll').addEventListener('change', () => {
  boxes.forEach((b) => { b.checked = $('agreeAll').checked; });
  sync();
});
boxes.forEach((b) => b.addEventListener('change', sync));

$('agreeForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  $('formAlert').hidden = true;
  const missing = boxes.find((b) => !b.checked);
  boxes.forEach((b) => b.setAttribute('aria-invalid', b.checked ? 'false' : 'true'));
  if (missing) {
    $('agreeError').textContent = '필수 항목에 모두 동의해 주세요.';
    missing.focus();
    return;
  }
  $('submitButton').disabled = true;
  try {
    await auth.agree();
    location.replace(next);
  } catch (e) {
    if (e.code === 'UNAUTHORIZED' || e.code === 'TOKEN_EXPIRED' || e.code === 'TOKEN_INVALID') {
      location.replace(auth.loginUrl(next));
      return;
    }
    $('formAlert').textContent = e.message;
    $('formAlert').hidden = false;
    $('formAlert').focus?.();
  } finally {
    $('submitButton').disabled = false;
  }
});

$('logoutButton').addEventListener('click', async () => {
  await auth.logout();
  location.replace('/');
});

async function init() {
  const user = await auth.init();
  if (!user) {
    location.replace(auth.loginUrl(`/agree.html?next=${encodeURIComponent(next)}`));
    return;
  }
  if (!user.needsAgreement) {
    location.replace(next);
    return;
  }
  $('lead').textContent = `${user.nickname}님, 이음을 계속 쓰려면 아래 내용에 동의해 주세요.`;
  $('card').hidden = false;
  // 전에 동의한 적이 있으면 약관이 바뀐 것 → 무엇이 바뀌었는지 알려 준다
  try {
    const [me, policy] = await Promise.all([auth.fetchMe(), api.policy()]);
    if (me.agreedAt) {
      $('lead').textContent = `${user.nickname}님, 이용약관과 개인정보 처리방침이 제${policy.version}판으로 바뀌었어요. 다시 동의해 주세요.`;
      $('changes').hidden = false;
    }
  } catch (_) { /* 안내 문구만 못 바꿀 뿐 동의는 할 수 있음 */ }
}

init();
