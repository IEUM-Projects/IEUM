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

/** 화면에 보여 줄 규칙 목록. ok가 모두 true면 통과 */
export function checkPassword(pw, { email = '', nickname = '' } = {}) {
  const lower = pw.toLowerCase();
  const kinds = kindCount(pw);
  const minLength = kinds >= 3 ? 10 : 12;
  return [
    { id: 'chars', label: '영문, 숫자, 표준 특수문자만 (공백·한글 불가)',
      ok: pw.length > 0 && [...pw].every((c) => isUpper(c) || isLower(c) || isDigit(c) || isSpecial(c)) },
    { id: 'kinds', label: '대문자·소문자·숫자·특수문자 중 2가지 이상', ok: kinds >= 2 },
    { id: 'length', label: '3가지 이상 조합이면 10자, 2가지면 12자 이상 (최대 64자)',
      ok: kinds >= 2 && pw.length >= minLength && pw.length <= 64 },
    { id: 'repeat', label: '같은 문자 3번 연속 없음 (예: aaa, 111)', ok: pw.length > 0 && !hasRepeat(pw) },
    { id: 'sequence', label: '연속된 문자 3개 없음, 거꾸로 포함 (예: qwe, abc, 123, 321)',
      ok: pw.length > 0 && !hasSequence(lower) },
    { id: 'dictionary', label: 'password, admin 같은 쉬운 단어 없음', ok: pw.length > 0 && !hasDictionaryWord(lower) },
    { id: 'personal', label: '이메일 아이디·닉네임 포함하지 않음', ok: pw.length > 0 && !hasPersonalInfo(lower, email, nickname) },
  ];
}
