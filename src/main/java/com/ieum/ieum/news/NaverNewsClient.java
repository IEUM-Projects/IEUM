package com.ieum.ieum.news;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 네이버 검색 API - 뉴스 (서버 전용 Client ID/Secret)
 * 2026년 9월부터 검색 API는 네이버 클라우드 플랫폼의 NAVER API HUB로 옮겨 갔다 (개발자센터 신규 신청 중단).
 * - API HUB(기본): https://naverapihub.apigw.ntruss.com/search/v1/news, 헤더 X-NCP-APIGW-API-KEY-ID / X-NCP-APIGW-API-KEY
 * - 개발자센터(예전 키가 있는 경우, 2027-06-30까지): naver.news-url 을 https://openapi.naver.com/v1/search/news.json 으로 바꾸면
 *   헤더 X-Naver-Client-Id / X-Naver-Client-Secret 로 부른다.
 * GET {news-url}?query=&display=(1~100)&start=1&sort=date|sim
 * 응답 items: title·description(검색어가 <b>로 감싸지고 HTML 문자 참조가 섞임), originallink(언론사 원문), link(네이버 뉴스), pubDate(RFC 1123)
 * 언론사 이름 칸은 없어서, 원문 주소의 도메인을 출처로 쓴다. 본문은 가져오지 않는다.
 */
@Slf4j
@Component
public class NaverNewsClient {

    /** 기사 하나. description 은 화면에 보여 주지 않고 관련 기사 거르기에만 쓴다 */
    public record Article(String title, String description, String url, String host, LocalDateTime publishedAt) {
    }

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|quot|amp|lt|gt|apos|nbsp|middot|hellip|lsquo|rsquo|ldquo|rdquo);");

    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String clientId;
    private final String clientSecret;
    private final String url;
    private final String idHeader;
    private final String secretHeader;

    public NaverNewsClient(@Value("${naver.client-id:}") String clientId,
                           @Value("${naver.client-secret:}") String clientSecret,
                           @Value("${naver.news-url:https://naverapihub.apigw.ntruss.com/search/v1/news}") String url) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.url = url;
        boolean developers = url.contains("openapi.naver.com") || url.contains("/v1/search/");
        this.idHeader = developers ? "X-Naver-Client-Id" : "X-NCP-APIGW-API-KEY-ID";
        this.secretHeader = developers ? "X-Naver-Client-Secret" : "X-NCP-APIGW-API-KEY";
        if (!hasKey()) {
            log.warn("네이버 검색 키(naver.client-id, naver.client-secret)가 없어서 시장·로컬 소식 기사를 불러오지 않아요. application-local.yml 을 확인하세요.");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(6));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean hasKey() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    public List<Article> search(String query, int display, boolean byDate) {
        URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParam("query", query).queryParam("display", Math.clamp(display, 1, 100))
                .queryParam("start", 1).queryParam("sort", byDate ? "date" : "sim")
                .encode().build().toUri();
        JsonNode root;
        try {
            String body = http.get().uri(uri).header(idHeader, clientId)
                    .header(secretHeader, clientSecret).retrieve().body(String.class);
            root = json.readTree(body == null ? "{}" : body);
        } catch (RestClientResponseException e) {   // 401(키)·403(권한)·429(하루 한도)·5xx: 상태와 네이버가 보낸 오류 내용을 남긴다 (키는 안 들어 있음)
            String detail = e.getResponseBodyAsString().replaceAll("\\s+", " ");
            throw new NewsException("네이버 뉴스 검색 실패: " + e.getStatusCode().value() + " "
                    + (detail.length() > 160 ? detail.substring(0, 160) : detail));
        } catch (RuntimeException e) {   // 연결·시간 초과·잘못된 JSON
            throw new NewsException("네이버 뉴스 검색 실패: " + e.getClass().getSimpleName());
        }
        JsonNode items = root.path("items");
        if (!items.isArray()) throw new NewsException("네이버 뉴스 검색: items 없음");
        List<Article> out = new ArrayList<>();
        for (JsonNode i : items.values()) {
            String title = clean(i.path("title").asString(""));
            String link = safeUrl(i.path("originallink").asString(""));
            if (link == null) link = safeUrl(i.path("link").asString(""));
            if (title.isBlank() || link == null) continue;
            out.add(new Article(cut(title, 200), cut(clean(i.path("description").asString("")), 400), link, host(link),
                    date(i.path("pubDate").asString(""))));
        }
        return out;
    }

    /** <b> 같은 태그를 빼고 &quot; 같은 문자 참조를 글자로 바꾼다 */
    static String clean(String s) {
        String noTags = TAG.matcher(s).replaceAll("");
        Matcher m = ENTITY.matcher(noTags);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String e = m.group(1);
            String r = switch (e) {
                case "quot" -> "\"";
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "apos" -> "'";
                case "nbsp" -> " ";
                case "middot" -> "·";
                case "hellip" -> "…";
                case "lsquo", "rsquo" -> "'";
                case "ldquo", "rdquo" -> "\"";
                default -> {
                    try {
                        int cp = e.startsWith("#x") ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                        yield Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : "";
                    } catch (NumberFormatException ex) {
                        yield "";
                    }
                }
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("\\s+", " ").strip();
    }

    /** http·https 주소만 (javascript: 같은 것은 버림) */
    static String safeUrl(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            URI u = URI.create(s.strip());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && u.getHost() != null ? u.toString() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 출처로 보여 줄 도메인 (www., m. 은 뺌) */
    static String host(String url) {
        String h = URI.create(url).getHost().toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        else if (h.startsWith("m.")) h = h.substring(2);
        return h;
    }

    static LocalDateTime date(String s) {
        try {
            return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).withZoneSameInstant(SEOUL).toLocalDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    public static class NewsException extends RuntimeException {
        public NewsException(String message) {
            super(message);
        }
    }
}
