package com.ieum.ieum.user;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 닉네임에 욕설·비속어나 운영자로 오해할 수 있는 말이 있는지 본다 (AUTH-01).
 * 말 목록은 classpath 의 nickname-banned.txt (운영자가 고칠 수 있게 코드 밖에 둠).
 *
 *   "시1발맨"  → 숫자를 빼고 "시발맨" → 막음
 *   "FuCk99"  → 소문자로 "fuck" → 막음
 *   "운영자김" → [예약] 단어 → 막음 (안내 문구가 다름)
 *   "시발점"   → [허용] 말이라 통과
 *
 * 닉네임은 글·댓글에 공개되므로 가입, 닉네임 변경, 카카오 첫 가입(자동 닉네임) 모두 이 검사를 거친다.
 */
@Component
public class NicknamePolicy {

    public enum Result { OK, BANNED, RESERVED }

    public static final String BANNED_MESSAGE = "욕설이나 비속어가 들어 있어 쓸 수 없어요.";
    public static final String RESERVED_MESSAGE = "운영자로 오해할 수 있는 말이 들어 있어 쓸 수 없어요.";
    static final String FILE = "nickname-banned.txt";

    private final List<String> banned = new ArrayList<>();
    private final List<String> reserved = new ArrayList<>();
    private final List<String> allowed = new ArrayList<>();

    public NicknamePolicy() {
        try (InputStream in = new ClassPathResource(FILE).getInputStream()) {
            List<String> target = banned;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String word = line.strip();
                if (word.isEmpty() || word.startsWith("#")) continue;
                if (word.equals("[예약]")) { target = reserved; continue; }
                if (word.equals("[허용]")) { target = allowed; continue; }
                target.add(normalize(word));
            }
        } catch (IOException e) {
            throw new IllegalStateException(FILE + " 을 읽지 못했어요.", e);
        }
    }

    public Result check(String nickname) {
        if (nickname == null || nickname.isBlank()) return Result.OK;
        String plain = withoutAllowed(nickname.toLowerCase(Locale.ROOT));
        String normalized = withoutAllowed(normalize(nickname));
        String digitsAsLetters = withoutAllowed(leet(nickname.toLowerCase(Locale.ROOT)));
        for (String word : banned) {
            if (plain.contains(word) || normalized.contains(word) || digitsAsLetters.contains(word)) return Result.BANNED;
        }
        for (String word : reserved) {
            if (plain.contains(word) || normalized.contains(word) || digitsAsLetters.contains(word)) return Result.RESERVED;
        }
        return Result.OK;
    }

    /** 쓸 수 없으면 안내 문구, 쓸 수 있으면 null */
    public String problem(String nickname) {
        return switch (check(nickname)) {
            case BANNED -> BANNED_MESSAGE;
            case RESERVED -> RESERVED_MESSAGE;
            case OK -> null;
        };
    }

    /** [허용] 말을 지운다 ("시발점" → "") */
    private String withoutAllowed(String s) {
        for (String word : allowed) s = s.replace(word, " ");
        return s;
    }

    /** 소문자로 바꾸고 숫자·빈칸을 뺀다 ("시1발" → "시발") */
    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[0-9\\s]", "");
    }

    /** 흔한 숫자 바꿔 쓰기를 글자로 ("sh1t" → "shit", "p0rn" → "porn") */
    static String leet(String lower) {
        return lower.replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a').replace('5', 's').replace('7', 't');
    }
}
