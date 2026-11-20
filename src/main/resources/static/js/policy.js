// 이용약관·개인정보 처리방침 화면: 판, 시행일, 운영자 정보를 서버 설정(ieum.policy.*)에서 채운다 (POL-01)
import { api } from './api.js';

const MISSING = '(운영자 정보 준비 중)';

function fill(info) {
  document.querySelectorAll('[data-policy]').forEach((node) => {
    const key = node.dataset.policy;
    const value = info?.[key] === undefined || info?.[key] === null ? '' : String(info[key]).trim();
    node.replaceChildren();
    node.classList.toggle('policy-missing', !value && node.dataset.empty === undefined);
    if (!value) {
      node.textContent = node.dataset.empty ?? MISSING;
      return;
    }
    if (node.hasAttribute('data-mailto')) {
      const a = document.createElement('a');
      a.href = `mailto:${value}`;
      a.textContent = value;
      node.append(a);
    } else {
      node.textContent = value;
    }
  });
}

async function init() {
  try {
    fill(await api.policy());
  } catch (_) {
    fill(null);
  }
  // 주소에 #p3 같은 위치가 있으면 내용이 채워진 뒤 그 자리로
  if (location.hash) document.getElementById(location.hash.slice(1))?.scrollIntoView();
}

init();
