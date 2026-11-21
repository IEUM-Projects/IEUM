package com.ieum.ieum.global.seo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 상세 화면 링크 미리보기 (SEO-02).
 * 카카오톡·검색 엔진은 자바스크립트를 돌리지 않아서, 상세 화면 HTML 의 제목·설명이 모두 '가게 상세'처럼 같았다.
 * 서버가 HTML 을 보낼 때 주소의 id 로 이름을 찾아 <title>·설명·og 태그를 채운다 (SiteUrlFilter 가 부른다).
 *
 *   /store.html?id=12     → "광장 빈대떡 - 이음"   / "한식 · 서울 종로구 … 백년가게의 위치와 영업 정보를 …"
 *   /market.html?id=3     → "광장시장 - 이음"
 *   /festival.html?id=9   → "○○ 축제 - 이음"       / "2026.10.3 ~ 10.5 · 장소"
 *   /post.html?id=5       → "글 제목 - 동네 이야기 - 이음" / 내용 앞부분, 사진이 있으면 첫 사진을 미리보기 그림으로
 *   /notice.html?id=2     → "공지 제목 - 공지사항 - 이음"
 *
 * 보이지 않는 것(숨긴 가게, 가려진·지운 글, 숨긴 공지, 없는 번호)은 손대지 않아 기본 미리보기 그대로 나간다.
 * 이름·내용은 모두 HTML 속성용으로 바꿔(escape) 넣는다.
 */
@Component
@RequiredArgsConstructor
public class DetailMeta {

    static final int DESC_MAX = 120;

    private final NamedParameterJdbcTemplate jdbc;

    /** image: 사이트 주소 뒤에 붙일 경로 (없으면 기본 그림) */
    public record Meta(String title, String description, String ogType, String image, Integer imageWidth, Integer imageHeight, String imageAlt) {
    }

    public Optional<Meta> find(String path, String idParam) {
        if (idParam == null || !idParam.matches("\\d{1,18}")) return Optional.empty();
        long id = Long.parseLong(idParam);
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        return switch (path) {
            case "/store.html" -> one("""
                    SELECT name, category, road_address, type FROM stores WHERE id = :id AND is_visible = 1""", p).map(r -> {
                String kind = "CREATOR".equals(r.get("type")) ? "로컬크리에이터" : "백년가게";
                return new Meta(r.get("name") + " - 이음",
                        desc(join((String) r.get("category"), (String) r.get("road_address")) + ". " + kind + "의 위치와 영업 정보를 이음에서 확인해 보세요."),
                        "website", null, null, null, null);
            });
            case "/market.html" -> one("SELECT name, road_address, market_days FROM markets WHERE id = :id", p).map(r -> {
                String days = (String) r.get("market_days");
                return new Meta(r.get("name") + " - 이음",
                        desc(join("전통시장", (String) r.get("road_address"), days == null || days.isBlank() ? null : "장날 " + days)
                                + ". 위치와 장날, 시장 이야기를 이음에서 확인해 보세요."),
                        "website", null, null, null, null);
            });
            case "/festival.html" -> one("SELECT name, place, start_date, end_date FROM festivals WHERE id = :id", p).map(r ->
                    new Meta(r.get("name") + " - 이음",
                            desc(join(period(r.get("start_date"), r.get("end_date")), (String) r.get("place")) + ". 축제 일정과 위치를 이음에서 확인해 보세요."),
                            "website", null, null, null, null));
            case "/post.html" -> one("SELECT title, content FROM posts WHERE id = :id AND status = 'VISIBLE'", p).map(r -> {
                List<Map<String, Object>> photo = jdbc.queryForList("""
                        SELECT file_key, width, height, alt FROM post_photos WHERE post_id = :id ORDER BY sort_order, id LIMIT 1""", p);
                String title = (String) r.get("title");
                String content = (String) r.get("content");
                if (photo.isEmpty()) return new Meta(title + " - 동네 이야기 - 이음", desc(content), "article", null, null, null, null);
                Map<String, Object> ph = photo.getFirst();
                String alt = (String) ph.get("alt");
                return new Meta(title + " - 동네 이야기 - 이음", desc(content), "article",
                        "/api/posts/photos/" + ph.get("file_key") + ".jpg",
                        ((Number) ph.get("width")).intValue(), ((Number) ph.get("height")).intValue(),
                        alt == null || alt.isBlank() ? title + " 사진" : alt);
            });
            case "/notice.html" -> one("SELECT title, content FROM notices WHERE id = :id AND visible = 1", p).map(r ->
                    new Meta(r.get("title") + " - 공지사항 - 이음", desc((String) r.get("content")), "article", null, null, null, null));
            default -> Optional.empty();
        };
    }

    private Optional<Map<String, Object>> one(String sql, MapSqlParameterSource p) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, p);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String s : parts) {
            if (s == null || s.isBlank()) continue;
            if (!b.isEmpty()) b.append(" · ");
            b.append(s.strip());
        }
        return b.toString();
    }

    private static String period(Object start, Object end) {
        LocalDate s = start instanceof Date d ? d.toLocalDate() : (LocalDate) start;
        LocalDate e = end instanceof Date d ? d.toLocalDate() : (LocalDate) end;
        if (s == null) return null;
        String a = s.getYear() + "." + s.getMonthValue() + "." + s.getDayOfMonth();
        if (e == null || e.equals(s)) return a;
        String b = e.getYear() == s.getYear() ? e.getMonthValue() + "." + e.getDayOfMonth() : e.getYear() + "." + e.getMonthValue() + "." + e.getDayOfMonth();
        return a + " ~ " + b;
    }

    /** 한 줄로, 120자까지 */
    static String desc(String s) {
        String one = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        if (one.startsWith(". ")) one = one.substring(2);
        return one.length() > DESC_MAX ? one.substring(0, DESC_MAX - 1) + "…" : one;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static final Pattern TITLE = Pattern.compile("<title>[^<]*</title>");
    private static final Pattern DESC = Pattern.compile("<meta name=\"description\" content=\"[^\"]*\">");
    private static final Pattern OG_TITLE = Pattern.compile("<meta property=\"og:title\" content=\"[^\"]*\">");
    private static final Pattern OG_DESC = Pattern.compile("<meta property=\"og:description\" content=\"[^\"]*\">");
    private static final Pattern OG_TYPE = Pattern.compile("<meta property=\"og:type\" content=\"[^\"]*\">");
    private static final Pattern OG_IMAGE = Pattern.compile("<meta property=\"og:image\" content=\"[^\"]*\">");
    private static final Pattern OG_W = Pattern.compile("<meta property=\"og:image:width\" content=\"[^\"]*\">");
    private static final Pattern OG_H = Pattern.compile("<meta property=\"og:image:height\" content=\"[^\"]*\">");
    private static final Pattern OG_ALT = Pattern.compile("<meta property=\"og:image:alt\" content=\"[^\"]*\">");

    /** HTML 의 머리 태그를 바꾼다. site: 사이트 주소, url: 이 화면 전체 주소 */
    static String apply(String html, Meta m, String site, String url) {
        String t = escape(m.title());
        String d = escape(m.description());
        String out = replace(html, TITLE, "<title>" + t + "</title>");
        out = replace(out, DESC, "<meta name=\"description\" content=\"" + d + "\">");
        out = replace(out, OG_TITLE, "<meta property=\"og:title\" content=\"" + t + "\">");
        out = replace(out, OG_DESC, "<meta property=\"og:description\" content=\"" + d + "\">");
        out = replace(out, OG_TYPE, "<meta property=\"og:type\" content=\"" + m.ogType() + "\">\n  <meta property=\"og:url\" content=\"" + escape(url) + "\">");
        if (m.image() != null) {
            out = replace(out, OG_IMAGE, "<meta property=\"og:image\" content=\"" + escape(site + m.image()) + "\">");
            out = replace(out, OG_W, "<meta property=\"og:image:width\" content=\"" + m.imageWidth() + "\">");
            out = replace(out, OG_H, "<meta property=\"og:image:height\" content=\"" + m.imageHeight() + "\">");
            out = replace(out, OG_ALT, "<meta property=\"og:image:alt\" content=\"" + escape(m.imageAlt()) + "\">");
        }
        return out;
    }

    private static String replace(String html, Pattern p, String to) {
        Matcher mt = p.matcher(html);
        return mt.find() ? html.substring(0, mt.start()) + to + html.substring(mt.end()) : html;
    }
}
