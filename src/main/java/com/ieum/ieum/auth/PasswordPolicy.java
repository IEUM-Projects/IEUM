package com.ieum.ieum.auth;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 표준 비밀번호 관리 규칙 검사.
 *
 * 1. 조합·길이
 *    - 영문 대문자, 영문 소문자, 숫자, 특수문자 중 3가지 이상 → 10자 이상
 *    - 2가지 조합                                       → 12자 이상
 *    - 1가지만 쓰면 사용할 수 없음
 *    - 특수문자는 표준 ASCII 특수문자만 (공백·한글 불가), 최대 64자
 * 2. 금지 패턴
 *    - 개인정보: 이메일 아이디, 닉네임 포함 금지
 *    - 같은 문자 3번 이상 연속 (aaa, 111)
 *    - 키보드 배열상 연속된 문자 3개 이상 (qwe, 123, asd, 거꾸로 포함)
 *    - 추측하기 쉬운 단어 (password, admin, love, welcome 등)
 *
 * check()는 어긴 규칙의 안내 문구를 모두 돌려준다. 비어 있으면 통과.
 */
@Component
public class PasswordPolicy {

    public static final int MAX_LENGTH = 64; // BCrypt는 72바이트까지만 사용하므로 여유 있게 제한

    private static final String SPECIALS = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

    /** 키보드 줄과 순서가 있는 문자열. 이 안에서 3글자 연속(정방향·역방향)이면 금지 */
    private static final List<String> SEQUENCES = List.of(
            "1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm",
            "abcdefghijklmnopqrstuvwxyz", "!@#$%^&*()");

    /** 추측하기 쉬운 단어. 대소문자 구분 없이, 흔한 글자 바꾸기(p@ssw0rd)도 잡는다 */
    private static final List<String> DICTIONARY = List.of(
            "password", "passwd", "admin", "love", "welcome", "qwerty", "letmein", "iloveyou",
            "login", "master", "hello", "dragon", "monkey", "sunshine", "princess", "football",
            "baseball", "superman", "korea", "seoul", "ieum");

    public List<String> check(String password, String email, String nickname) {
        List<String> errors = new ArrayList<>();
        if (password == null || password.isEmpty()) {
            errors.add("비밀번호를 입력해 주세요.");
            return errors;
        }

        // ---- 1. 조합·길이
        boolean invalidChar = password.chars().anyMatch(c -> !(isUpper(c) || isLower(c) || isDigit(c) || isSpecial(c)));
        if (invalidChar) {
            errors.add("영문, 숫자, 표준 특수문자만 사용할 수 있어요. (공백·한글 불가)");
        }
        int kinds = (password.chars().anyMatch(PasswordPolicy::isUpper) ? 1 : 0)
                + (password.chars().anyMatch(PasswordPolicy::isLower) ? 1 : 0)
                + (password.chars().anyMatch(PasswordPolicy::isDigit) ? 1 : 0)
                + (password.chars().anyMatch(PasswordPolicy::isSpecial) ? 1 : 0);
        if (kinds < 2) {
            errors.add("영문 대문자, 영문 소문자, 숫자, 특수문자 중 2가지 이상을 조합해 주세요.");
        } else if (kinds == 2 && password.length() < 12) {
            errors.add("2가지를 조합하면 12자 이상이어야 해요. (3가지 이상 조합하면 10자 이상)");
        } else if (kinds >= 3 && password.length() < 10) {
            errors.add("10자 이상 입력해 주세요.");
        }
        if (password.length() > MAX_LENGTH) {
            errors.add(MAX_LENGTH + "자 이하로 입력해 주세요.");
        }

        // ---- 2. 금지 패턴
        String lower = password.toLowerCase(Locale.ROOT);

        if (containsPersonalInfo(lower, email, nickname)) {
            errors.add("이메일 아이디나 닉네임을 포함할 수 없어요.");
        }
        if (hasRepeatedChars(password)) {
            errors.add("같은 문자를 3번 이상 연속으로 쓸 수 없어요. (예: aaa, 111)");
        }
        if (hasKeyboardSequence(lower)) {
            errors.add("키보드나 알파벳·숫자 순서로 연속된 문자를 3개 이상 쓸 수 없어요. 거꾸로도 안 돼요. (예: qwe, ewq, abc, 123, 321)");
        }
        if (containsDictionaryWord(lower)) {
            errors.add("password, admin, love처럼 추측하기 쉬운 단어는 쓸 수 없어요.");
        }
        return errors;
    }

    private static boolean isUpper(int c) { return c >= 'A' && c <= 'Z'; }
    private static boolean isLower(int c) { return c >= 'a' && c <= 'z'; }
    private static boolean isDigit(int c) { return c >= '0' && c <= '9'; }
    private static boolean isSpecial(int c) { return SPECIALS.indexOf(c) >= 0; }

    private static boolean containsPersonalInfo(String lowerPassword, String email, String nickname) {
        if (email != null) {
            String id = email.toLowerCase(Locale.ROOT).split("@")[0];
            if (id.length() >= 3 && lowerPassword.contains(id)) return true;
        }
        if (nickname != null) {
            String nick = nickname.toLowerCase(Locale.ROOT);
            if (nick.length() >= 3 && lowerPassword.contains(nick)) return true;
        }
        return false;
    }

    private static boolean hasRepeatedChars(String password) {
        for (int i = 2; i < password.length(); i++) {
            if (password.charAt(i) == password.charAt(i - 1) && password.charAt(i) == password.charAt(i - 2)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasKeyboardSequence(String lowerPassword) {
        for (int i = 0; i + 3 <= lowerPassword.length(); i++) {
            String part = lowerPassword.substring(i, i + 3);
            String reversed = new StringBuilder(part).reverse().toString();
            for (String seq : SEQUENCES) {
                if (seq.contains(part) || seq.contains(reversed)) return true;
            }
        }
        return false;
    }

    private static boolean containsDictionaryWord(String lowerPassword) {
        // 흔한 글자 바꾸기를 되돌린 뒤 검사: p@ssw0rd → password, l0ve → love
        String normalized = lowerPassword
                .replace('@', 'a').replace('4', 'a')
                .replace('0', 'o').replace('1', 'l').replace('!', 'i')
                .replace('3', 'e').replace('$', 's').replace('5', 's').replace('7', 't');
        for (String word : DICTIONARY) {
            if (lowerPassword.contains(word) || normalized.contains(word)) return true;
        }
        return false;
    }
}
