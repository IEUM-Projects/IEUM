package com.ieum.ieum.community;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 동네 이야기 글·댓글의 욕설·비속어 거르기 (COM-03).
 * 말 목록은 classpath 의 community-banned.txt (운영자가 고칠 수 있게 코드 밖에 둠). 규칙은 그 파일 머리말에.
 *
 *   "씨.발 진짜"   → 낱말 '씨.발'에서 기호를 빼면 '씨발' → 막음
 *   "FUCK"        → 소문자 'fuck' → 막음
 *   "투병 신청"    → 낱말마다 보므로 '병신'이 안 생김 → 통과
 *   "시발점에서"   → [허용] '시발점'을 먼저 지움 → 통과
 *   "Scunthorpe"  → 영어 금지어는 영어 낱말의 '앞부분'만 본다 (fucking·bitches 는 막고, 가운데 든 것은 통과)
 *
 * 걸리면 글을 바꾸지 않고(***로 가리지 않고) 올리기를 막는다. 어느 칸의 어떤 말인지 알려 줘서 글쓴이가 고치게 한다.
 */
@Component
public class ProfanityFilter {

    static final String FILE = "community-banned.txt";

    private final List<String> banned = new ArrayList<>();
    private final java.util.Map<String, String> shown = new java.util.HashMap<>();   // 비교용 → 파일에 적힌 모양 (안내 문구용)
    private final List<String> allowed = new ArrayList<>();
    private static final java.util.regex.Pattern LATIN = java.util.regex.Pattern.compile("[a-z]+");

    public ProfanityFilter() {
        try (InputStream in = new ClassPathResource(FILE).getInputStream()) {
            List<String> target = banned;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String word = line.strip();
                if (word.isEmpty() || word.startsWith("#")) continue;
                if (word.equals("[허용]")) { target = allowed; continue; }
                target.add(normalize(word));
                shown.putIfAbsent(normalize(word), word);
            }
        } catch (IOException e) {
            throw new IllegalStateException(FILE + " 을 읽지 못했어요.", e);
        }
        // 긴 말부터 찾아야 알려 주는 말이 정확하다 ('개새끼'를 '새끼'보다 먼저)
        banned.sort((a, b) -> b.length() - a.length());
    }

    /** 처음 찾은 금지어. 없으면 빈 값 */
    public Optional<String> find(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String lower = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        for (String word : allowed) lower = lower.replace(word, " ");
        for (String token : lower.split("\\s+")) {
            String letters = lettersOnly(token);
            String leet = lettersOnly(leet(token));
            for (String word : banned) {
                boolean hit = isLatin(word)
                        ? startsLatinWord(letters, word) || startsLatinWord(leet, word)
                        : letters.contains(word) || leet.contains(word);
                if (hit) return Optional.of(shown.getOrDefault(word, word));
            }
        }
        return Optional.empty();
    }

    /**
     * 걸리면 PROFANITY 오류. field 는 '제목', '내용', '댓글', '사진 설명' 같은 칸 이름.
     * details[0] 은 화면에 그대로 보여 줄 문장이다.
     */
    public void check(String field, String text) {
        find(text).ifPresent(word -> {
            throw new BusinessException(ErrorCode.PROFANITY,
                    List.of(field + "에 쓸 수 없는 말(‘" + word + "’)이 있어요. 고쳐서 다시 올려 주세요."));
        });
    }

    private static boolean isLatin(String word) {
        return LATIN.matcher(word).matches();
    }

    /** 낱말 안의 영어 덩어리 중 하나가 word 로 시작하는지 ("진짜fucking" → fucking → 막음, "scunthorpe" → 통과) */
    private static boolean startsLatinWord(String letters, String word) {
        java.util.regex.Matcher m = LATIN.matcher(letters);
        while (m.find()) {
            if (m.group().startsWith(word)) return true;
        }
        return false;
    }

    /** 글자(한글·자모·영문)만 남긴다: 숫자·기호·이모지를 빼서 '시1발', '씨.발'을 '시발', '씨발'로 */
    static String lettersOnly(String s) {
        return s.replaceAll("[^\\p{L}]", "");
    }

    static String normalize(String s) {
        return lettersOnly(Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
    }

    /** 흔한 숫자 바꿔 쓰기를 글자로 ("sh1t" → "shit") */
    static String leet(String lower) {
        return lower.replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a').replace('5', 's').replace('7', 't')
                .replace('@', 'a').replace('$', 's').replace('!', 'i').replace('|', 'l');
    }
}
