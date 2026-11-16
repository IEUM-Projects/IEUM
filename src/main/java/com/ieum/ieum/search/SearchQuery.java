package com.ieum.ieum.search;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;

import java.util.Arrays;
import java.util.List;

/**
 * 검색어 정리.
 * - 앞뒤 공백·보이지 않는 글자를 지우고, 여러 칸 띄어쓰기는 한 칸으로
 * - 1~30자, 낱말은 앞에서부터 5개까지 (낱말마다 모두 들어 있어야 찾음: '전주 국밥' → 전주 AND 국밥)
 * - LIKE 검색에서 %, _, \ 는 글자 그대로 찾도록 바꾼다 ('100%' 를 검색해도 모든 가게가 나오지 않게)
 */
public record SearchQuery(String text, List<String> words) {

    public static final int MAX_LENGTH = 30;
    public static final int MAX_WORDS = 5;

    public static SearchQuery parse(String raw) {
        String text = raw == null ? "" : raw.replaceAll("\\p{Cc}", " ").replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("검색어를 입력해 주세요."));
        }
        if (text.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("검색어는 30자까지 쓸 수 있어요."));
        }
        List<String> words = Arrays.stream(text.split(" ")).distinct().limit(MAX_WORDS).toList();
        return new SearchQuery(text, words);
    }

    /** LIKE 패턴: %낱말% (특수 글자는 그대로 찾도록 \ 로 감싼다) */
    public static String contains(String word) {
        return "%" + escape(word) + "%";
    }

    public static String startsWith(String word) {
        return escape(word) + "%";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
