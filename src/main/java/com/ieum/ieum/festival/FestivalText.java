package com.ieum.ieum.festival;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** API에서 받은 글자를 정리하는 도우미 */
final class FestivalText {

    private FestivalText() {
    }

    private static final Pattern ROUND_PREFIX = Pattern.compile("^(제\\s*)?\\d+\\s*(회|번째)\\.?\\s*");
    private static final Pattern YEAR_PREFIX = Pattern.compile("^(19|20)\\d{2}(년)?\\s*");
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]");
    private static final Pattern HREF = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern BR = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);

    /**
     * 같은 축제인지 비교하기 위한 이름.
     * "제21회 중문칠선녀축제", "2026 중문 칠선녀 축제" → "중문칠선녀축제"
     */
    static String nameKey(String name) {
        String s = name.trim();
        for (int i = 0; i < 2; i++) {   // "2026 제3회 ..." 처럼 둘 다 붙은 경우
            s = YEAR_PREFIX.matcher(s).replaceFirst("");
            s = ROUND_PREFIX.matcher(s).replaceFirst("");
        }
        String key = NOT_LETTER_OR_DIGIT.matcher(s).replaceAll("").toLowerCase();
        return key.isEmpty() ? NOT_LETTER_OR_DIGIT.matcher(name).replaceAll("").toLowerCase() : key;
    }

    /** 앞뒤 공백을 지우고, 빈 값은 null */
    static String clean(String value) {
        if (value == null) return null;
        String s = value.replace('\u00a0', ' ').trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    /** 칸 길이보다 길면 자른다 (DB 오류 방지) */
    static String cut(String value, int max) {
        String s = clean(value);
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    /** 2026-10-07 또는 20261007 → 날짜. 형식이 틀리면 null */
    static LocalDate date(String value) {
        String s = clean(value);
        if (s == null) return null;
        try {
            if (s.matches("\\d{8}")) {
                return LocalDate.of(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(4, 6)),
                        Integer.parseInt(s.substring(6, 8)));
            }
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    /** 위도·경도 글자 → 숫자. 우리나라 범위를 벗어나면 null */
    static Double latitude(String value) {
        Double d = number(value);
        return d != null && d >= 33 && d <= 39 ? d : null;
    }

    static Double longitude(String value) {
        Double d = number(value);
        return d != null && d >= 124 && d <= 132 ? d : null;
    }

    private static Double number(String value) {
        String s = clean(value);
        if (s == null) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 홈페이지 주소 정리.
     * 관광공사는 <a href="http://...">...</a> 형태로 주기도 하고, 표준데이터는 www.로 시작하기도 한다.
     * http/https 주소만 남긴다 (javascript: 같은 위험한 주소가 화면 링크로 들어가지 않게).
     */
    static String homepage(String value) {
        String s = clean(value);
        if (s == null) return null;
        Matcher m = HREF.matcher(s);
        if (m.find()) s = m.group(1).trim();
        s = TAG.matcher(s).replaceAll("").trim();
        // "www.abc.kr", "suyeong.go.kr/festival"처럼 http://가 빠진 주소
        if (!s.matches("(?i)^[a-z][a-z0-9+.-]*:.*") && s.matches("(?i)^[a-z0-9-]+(\\.[a-z0-9-]+)+(/\\S*)?$")) s = "http://" + s;
        if (!s.matches("(?i)^https?://[^\\s<>\"]+$")) return null;
        return cut(s, 500);
    }

    /** 관광공사 소개글의 HTML 태그를 지우고 줄바꿈만 남긴다 */
    static String plainText(String html) {
        String s = clean(html);
        if (s == null) return null;
        s = BR.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        s = s.replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n");
        return clean(s);
    }
}
