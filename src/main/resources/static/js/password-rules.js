// 비밀번호 규칙을 입력하는 동안 바로 보여 주기 위한 검사.
// 서버의 PasswordPolicy.java와 같은 규칙이다. 최종 판단은 서버가 하므로, 둘이 달라지면 서버 쪽이 맞다.

const SPECIALS = '!"#$%&\'()*+,-./:;<=>?@[\\]^_`{|}~';
const SEQUENCES = ['1234567890', 'qwertyuiop', 'asdfghjkl', 'zxcvbnm', 'abcdefghijklmnopqrstuvwxyz', '!@#$%^&*()'];
const DICTIONARY = ['password', 'passwd', 'admin', 'love', 'welcome', 'qwerty', 'letmein', 'iloveyou',
  'login', 'master', 'hello', 'dragon', 'monkey', 'sunshine', 'princess', 'football',
  'baseball', 'superman', 'korea', 'seoul', 'ieum'];

const isUpper = (c) => c >= 'A' && c <= 'Z';
const isLower = (c) => c >= 'a' && c <= 'z';
const isDigit = (c) => c >= '0' && c <= '9';
const isSpecial = (c) => SPECIALS.includes(c);

function kindCount(pw) {
  const chars = [...pw];
  return [isUpper, isLower, isDigit, isSpecial].filter((test) => chars.some(test)).length;
}

function hasRepeat(pw) {
  return /(.)\1\1/.test(pw);
}

function hasSequence(lower) {
  for (let i = 0; i + 3 <= lower.length; i++) {
    const part = lower.slice(i, i + 3);
    const reversed = [...part].reverse().join('');
    if (SEQUENCES.some((seq) => seq.includes(part) || seq.includes(reversed))) return true;
  }
  return false;
}

function hasDictionaryWord(lower) {
  const normalized = lower
    .replaceAll('@', 'a').replaceAll('4', 'a').replaceAll('0', 'o').replaceAll('1', 'l')
    .replaceAll('!', 'i').replaceAll('3', 'e').replaceAll('$', 's').replaceAll('5', 's').replaceAll('7', 't');
  return DICTIONARY.some((word) => lower.includes(word) || normalized.includes(word));
}

function hasPersonalInfo(lower, email, nickname) {
  const id = (email ?? '').toLowerCase().split('@')[0];
  const nick = (nickname ?? '').toLowerCase();
  return (id.length >= 3 && lower.includes(id)) || (nick.length >= 3 && lower.includes(nick));
}

/**
 * 화면에 보여 줄 규칙 목록. ok가 모두 true면 통과.
 * main: 늘 보여 주는 두 가지(섞기, 길이). 나머지는 어겼을 때만 그 줄을 보여 준다 (UI: 목록을 짧게).
 */
export function checkPassword(pw, { email = '', nickname = '' } = {}) {
  const lower = pw.toLowerCase();
  const kinds = kindCount(pw);
  const minLength = kinds >= 3 ? 10 : 12;
  const lengthLabel = pw.length > 64 ? '64자 이하로 써 주세요'
    : (kinds >= 3 ? '10자 이상' : '12자 이상 (3가지를 섞으면 10자)');
  return [
    { id: 'kinds', main: true, label: '대·소문자, 숫자, 특수문자 중 2가지 이상', ok: kinds >= 2 },
    { id: 'length', main: true, label: lengthLabel, count: pw.length,
      ok: kinds >= 2 && pw.length >= minLength && pw.length <= 64 },
    { id: 'chars', label: '공백이나 한글은 쓸 수 없어요',
      ok: pw.length > 0 && [...pw].every((c) => isUpper(c) || isLower(c) || isDigit(c) || isSpecial(c)) },
    { id: 'repeat', label: '같은 글자를 3번 연속 쓸 수 없어요 (예: aaa, 111)', ok: pw.length > 0 && !hasRepeat(pw) },
    { id: 'sequence', label: '이어지는 글자 3개는 쓸 수 없어요 (예: abc, 123, qwe, 321)',
      ok: pw.length > 0 && !hasSequence(lower) },
    { id: 'dictionary', label: 'password, admin 같은 쉬운 단어가 들어 있어요', ok: pw.length > 0 && !hasDictionaryWord(lower) },
    { id: 'personal', label: '이메일 아이디나 닉네임이 들어 있어요', ok: pw.length > 0 && !hasPersonalInfo(lower, email, nickname) },
  ];
}

/**
 * 규칙 목록을 <ul>에 그린다 (가입·비밀번호 찾기·내 정보가 함께 씀).
 * 늘 보이는 두 줄(섞기·길이)은 맞으면 초록 체크, touched(한 번 제출해 봄)면 아직인 줄도 빨갛게.
 * 나머지 규칙은 어겼을 때만 빨간 줄로 나타난다. 모두 맞으면 true.
 */
export function renderRuleList(list, pw, { email = '', nickname = '', touched = false } = {}) {
  const rules = checkPassword(pw, { email, nickname });
  const shown = rules.filter((rule) => rule.main || (pw.length > 0 && !rule.ok));
  list.replaceChildren(...shown.map((rule) => {
    const li = document.createElement('li');
    li.className = rule.ok ? 'ok' : (touched || !rule.main ? 'bad' : '');
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
