// 로그인 상태 관리 (모든 화면 공통)
//
// - Access Token은 이 파일의 변수(메모리)에만 둔다. localStorage에 두지 않는 이유:
//   화면에 악성 스크립트가 끼어들면 localStorage는 그대로 읽히기 때문.
// - 페이지를 옮기면 메모리가 비워지므로, 화면이 열릴 때 init()이 HttpOnly 쿠키로 재발급받는다.
// - API가 "토큰 만료"로 응답하면 authFetch()가 자동으로 재발급받고 요청을 한 번 다시 보낸다.

const LOGGED_IN_FLAG = 'ieum_logged_in'; // 로그인 여부 표시만 (토큰 아님). 없으면 재발급 요청을 생략
const NEXT_KEY = 'ieum_next';            // 카카오 로그인 후 돌아갈 화면
const REFRESH_LOCK = 'ieum-auth-refresh';

let accessToken = null;
let currentUser = null;
let refreshing = null;
const listeners = new Set();

/** 서버가 보낸 오류를 담는 예외. code로 종류를 구분하고 message는 사용자에게 그대로 보여 준다 */
export class AuthError extends Error {
  constructor({ code = 'ERROR', message = '잠시 후 다시 시도해 주세요.', details = [] } = {}) {
    super(message);
    this.code = code;
    this.details = details;
  }
}

// ---------------------------------------------------------------- 상태

function setFlag(on) {
  try {
    if (on) localStorage.setItem(LOGGED_IN_FLAG, '1');
    else localStorage.removeItem(LOGGED_IN_FLAG);
  } catch (_) { /* 저장소를 쓸 수 없는 환경(사생활 보호 모드 등)이면 무시 */ }
}

function hasFlag() {
  try {
    return localStorage.getItem(LOGGED_IN_FLAG) === '1';
  } catch (_) {
    return true; // 확인할 수 없으면 일단 재발급을 시도
  }
}

// 동의 전에도 열 수 있는 화면 (동의 화면과, 거기서 읽어 보는 약관·방침)
// (내 정보는 동의하지 않고 탈퇴할 수 있게 열어 둔다)
const AGREE_FREE_PAGES = ['/agree.html', '/terms.html', '/privacy.html', '/mypage.html'];

/**
 * 지금 판의 이용약관·개인정보 처리방침에 아직 동의하지 않은 회원(카카오로 처음 가입, 약관이 바뀐 뒤 첫 로그인)은
 * 동의 화면으로 보낸다. 동의하면 원래 보던 화면으로 돌아온다 (POL-01)
 */
function goAgreeIfNeeded(user) {
  if (!user?.needsAgreement || AGREE_FREE_PAGES.includes(location.pathname)) return false;
  location.replace(`/agree.html?next=${encodeURIComponent(safeNext(location.pathname + location.search))}`);
  return true;
}

function setSession({ accessToken: token, user }) {
  accessToken = token;
  currentUser = user;
  setFlag(true);
  listeners.forEach((fn) => fn(currentUser));
}

function clearSession() {
  accessToken = null;
  currentUser = null;
  setFlag(false);
  listeners.forEach((fn) => fn(null));
}

/** 로그인 상태가 바뀔 때마다 호출된다 (로그인하면 user, 로그아웃하면 null) */
export function onAuthChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function getUser() {
  return currentUser;
}

// ---------------------------------------------------------------- 재발급

async function requestRefresh() {
  const res = await fetch('/api/auth/refresh', { method: 'POST', credentials: 'same-origin' });
  if (!res.ok) {
    clearSession();
    return null;
  }
  setSession(await res.json());
  return currentUser;
}

/**
 * Refresh Token은 쓸 때마다 바뀐다. 탭 두 개가 동시에 재발급하면 늦은 쪽이 폐기된 토큰을 보내 로그아웃된다.
 * - 같은 탭 안: 진행 중인 재발급이 있으면 그 결과를 같이 기다린다.
 * - 탭끼리: Web Locks로 한 번에 하나씩. 뒤 탭은 앞 탭이 받아 둔 새 쿠키로 요청하게 된다.
 */
export function refresh() {
  if (!refreshing) {
    const run = () => requestRefresh();
    const task = navigator.locks ? navigator.locks.request(REFRESH_LOCK, run) : run();
    refreshing = task.finally(() => { refreshing = null; });
  }
  return refreshing;
}

/**
 * 화면이 열릴 때 한 번 호출. 로그인 상태면 회원 정보, 아니면 null
 * agreeRedirect: 동의가 필요한 회원이면 동의 화면으로 보냄 (로그인·가입 화면처럼 스스로 보내는 곳은 false)
 */
export async function init({ force = false, agreeRedirect = true } = {}) {
  if (!currentUser) {
    if (!force && !hasFlag()) return null;
    await refresh();
  }
  if (agreeRedirect) goAgreeIfNeeded(currentUser);
  return currentUser;
}

// ---------------------------------------------------------------- 요청

async function readError(res) {
  try {
    return new AuthError(await res.json());
  } catch (_) {
    return new AuthError({ code: `HTTP_${res.status}` });
  }
}

/** 로그인이 필요한 API 요청. 토큰을 자동으로 붙이고, 만료되면 재발급 후 한 번 다시 보낸다 */
export async function authFetch(url, options = {}) {
  const send = () => fetch(url, {
    ...options,
    headers: { ...(options.headers ?? {}), ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}) },
  });
  let res = await send();
  if (res.status === 401) {
    const { code } = await res.clone().json().catch(() => ({}));
    if ((code === 'TOKEN_EXPIRED' || code === 'TOKEN_INVALID' || code === 'UNAUTHORIZED') && hasFlag()) {
      if (await refresh()) res = await send();
    }
  }
  return res;
}

async function postJson(url, body) {
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin',
    body: JSON.stringify(body),
  });
}

// ---------------------------------------------------------------- 로그인·가입·로그아웃

export async function login(email, password) {
  const res = await postJson('/api/auth/login', { email, password });
  if (!res.ok) throw await readError(res);
  setSession(await res.json());
  return currentUser;
}

/** 가입 후 바로 로그인 (agreeTerms, agreePrivacy, over14: 가입 화면의 동의 세 가지) */
export async function signupAndLogin({ email, password, nickname, agreeTerms, agreePrivacy, over14 }) {
  const res = await postJson('/api/auth/signup', { email, password, nickname, agreeTerms, agreePrivacy, over14 });
  if (!res.ok) throw await readError(res);
  try {
    return await login(email, password);
  } catch (_) {
    // 가입은 됐는데 자동 로그인만 실패한 경우: 다시 가입하면 "이미 가입된 이메일"이 되므로 따로 알린다
    throw new AuthError({ code: 'SIGNED_UP_LOGIN_FAILED', message: '가입은 완료됐어요. 로그인해 주세요.' });
  }
}

export async function logout() {
  try {
    await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' });
  } finally {
    clearSession();
  }
}

export async function fetchMe() {
  const res = await authFetch('/api/users/me');
  if (!res.ok) throw await readError(res);
  return res.json();
}

/** 영업 정보 제보 보내기. type: CLOSED / HOURS / PHONE / LOCATION / OTHER */
export async function sendStoreReport(storeId, { type, content }) {
  const res = await authFetch(`/api/stores/${encodeURIComponent(storeId)}/reports`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type, content }),
  });
  if (!res.ok) throw await readError(res);
  return res.json();
}

/** 내 제보 목록 (최근 50건) */
export async function fetchMyReports() {
  const res = await authFetch('/api/users/me/reports');
  if (!res.ok) throw await readError(res);
  return res.json();
}

export async function updateNickname(nickname) {
  const res = await authFetch('/api/users/me', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ nickname }),
  });
  if (!res.ok) throw await readError(res);
  const me = await res.json();
  currentUser = { ...currentUser, nickname: me.nickname };
  listeners.forEach((fn) => fn(currentUser));
  return me;
}

/** 비밀번호 바꾸기 (AUTH-04): 이 기기의 로그인은 그대로, 다른 기기는 로그아웃 */
export async function changePassword(currentPassword, newPassword) {
  const res = await authFetch('/api/auth/password', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ currentPassword, newPassword }),
  });
  if (!res.ok) throw await readError(res);
}

/** 이용약관·개인정보 수집·이용·만 14세 이상 동의 (POL-01) */
export async function agree() {
  const res = await authFetch('/api/users/me/agreements', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ agreeTerms: true, agreePrivacy: true, over14: true }),
  });
  if (!res.ok) throw await readError(res);
  const me = await res.json();
  currentUser = { ...currentUser, needsAgreement: false };
  listeners.forEach((fn) => fn(currentUser));
  return me;
}

/** 회원 탈퇴. 이메일 회원은 { password }, 카카오 회원은 { confirm: '탈퇴' } (POL-01) */
export async function withdraw({ password, confirm }) {
  const res = await authFetch('/api/users/me', {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password, confirm }),
  });
  if (!res.ok) throw await readError(res);
  clearSession();
}

// ---------------------------------------------------------------- 카카오 로그인, 돌아갈 화면

/**
 * 로그인 후 돌아갈 주소는 우리 사이트 안의 주소만 허용한다.
 * "https://..."나 "//evil.com" 같은 바깥 주소를 허용하면, 로그인 직후 가짜 사이트로 보내는 피싱에 쓰일 수 있다.
 */
export function safeNext(next) {
  if (typeof next !== 'string' || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) {
    return '/';
  }
  return next;
}

export function kakaoLogin(next = '/') {
  try {
    sessionStorage.setItem(NEXT_KEY, safeNext(next));
  } catch (_) { /* 저장 못 하면 지도 화면으로 돌아감 */ }
  location.href = '/oauth2/authorization/kakao';
}

/** 카카오 로그인에서 돌아왔을 때, 로그인 전에 보던 화면 주소 (한 번 꺼내면 지움) */
export function consumeNext() {
  try {
    const next = sessionStorage.getItem(NEXT_KEY);
    sessionStorage.removeItem(NEXT_KEY);
    return safeNext(next);
  } catch (_) {
    return '/';
  }
}

/** 로그인한 뒤 갈 곳: 동의가 필요하면 동의 화면(끝나면 next로), 아니면 next (POL-01) */
export function afterLogin(user, next = '/') {
  const target = safeNext(next);
  return user?.needsAgreement ? `/agree.html?next=${encodeURIComponent(target)}` : target;
}

/** 로그인 화면 주소 (지금 보던 화면으로 돌아오게) */
export function loginUrl(next = location.pathname + location.search) {
  return `/login.html?next=${encodeURIComponent(safeNext(next))}`;
}
