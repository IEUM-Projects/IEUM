package com.ieum.ieum.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 소셜 로그인 닉네임을 우리 규칙(한글·영문·숫자 2~12자, 중복 불가)에 맞게 만든다.
 *
 *   "병선"           → "병선"  (이미 있으면 "병선2", "병선3" ...)
 *   "홍 길동!! 🍀"    → "홍길동" (규칙에 없는 문자 제거)
 *   "A" 또는 ""      → "이음회원" (너무 짧으면 기본 이름)
 *   13자 이상         → 앞 12자
 */
@Component
@RequiredArgsConstructor
public class NicknameGenerator {

    static final String DEFAULT_NICKNAME = "이음회원";
    static final int MAX_LENGTH = 12;

    private final UserRepository userRepository;

    public String generate(String rawNickname) {
        String base = sanitize(rawNickname);
        if (!userRepository.existsByNickname(base)) {
            return base;
        }
        // 뒤에 숫자를 붙이되, 전체 길이가 12자를 넘지 않도록 앞부분을 줄인다
        for (int n = 2; n < 1000; n++) {
            String candidate = withSuffix(base, String.valueOf(n));
            if (!userRepository.existsByNickname(candidate)) {
                return candidate;
            }
        }
        // 같은 이름이 1000개 가까이 있는 드문 경우: 무작위 숫자
        while (true) {
            String candidate = withSuffix(base, String.valueOf(ThreadLocalRandom.current().nextInt(1000, 1_000_000)));
            if (!userRepository.existsByNickname(candidate)) {
                return candidate;
            }
        }
    }

    static String sanitize(String rawNickname) {
        String cleaned = rawNickname == null ? "" : rawNickname.replaceAll("[^가-힣a-zA-Z0-9]", "");
        if (cleaned.length() > MAX_LENGTH) {
            cleaned = cleaned.substring(0, MAX_LENGTH);
        }
        return cleaned.length() < 2 ? DEFAULT_NICKNAME : cleaned;
    }

    private static String withSuffix(String base, String suffix) {
        int keep = Math.min(base.length(), MAX_LENGTH - suffix.length());
        return base.substring(0, keep) + suffix;
    }
}
