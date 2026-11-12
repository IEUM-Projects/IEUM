// 카카오 로그인에서 돌아왔을 때 처리 (/?login=success 또는 /?login=failed&reason=...)
// 서버(OAuth2LoginHandlers)는 항상 첫 화면(/)으로 돌려보낸다. 첫 화면(홈)이 이 모듈로 결과를 처리한다.
import * as auth from './auth.js';
import { LOGIN_FAILURE_MESSAGES } from './api.js';

/**
 * 반환값: 다른 화면으로 이동했으면 true (호출한 화면은 더 그리지 않아도 됨)
 * notify(message): 결과 안내를 보여 줄 함수 (토스트 등)
 */
export async function handleLoginReturn(notify) {
  const params = new URLSearchParams(location.search);
  const result = params.get('login');
  if (!result) {
    await auth.init();
    return false;
  }
  history.replaceState(null, '', location.pathname);
  if (result === 'success') {
    const user = await auth.init({ force: true });
    const next = auth.consumeNext();
    // 로그인 전에 보던 화면이 있으면 그리로 (예: 가게 화면에서 '카카오로 시작하기')
    if (user && next !== '/') {
      location.replace(next);
      return true;
    }
    notify(user ? `${user.nickname}님, 카카오로 로그인했어요.` : '다시 로그인해 주세요.');
  } else {
    auth.consumeNext();
    notify(LOGIN_FAILURE_MESSAGES[params.get('reason')] ?? LOGIN_FAILURE_MESSAGES.ERROR);
  }
  return false;
}
