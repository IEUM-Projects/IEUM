package com.ieum.ieum.global.seo;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 검색 엔진용 안내 (SEO-01)
 *
 * GET /            : 첫 화면 (index.html, 아래 home 설명)
 * GET /robots.txt  : 로그인·관리자·API 처럼 검색에 나올 필요 없는 곳은 빼 달라고 알리고, sitemap 위치를 알려 준다.
 * GET /sitemap.xml : 검색에 나오면 좋은 화면 목록 (주요 화면 + 가게·시장·축제·공지 상세). 1시간 캐시.
 *
 * 로그인·회원가입처럼 막을 필요는 없지만 검색 결과에 나올 필요도 없는 화면은 HTML 의 robots noindex 로 뺀다.
 * (robots.txt 로 막으면 검색 엔진이 noindex 를 읽지 못해 주소만 검색 결과에 남을 수 있어서)
 */
@RestController
@RequiredArgsConstructor
public class SeoController {

    /** 주소가 하나뿐인 화면 (상세 화면처럼 ?id= 가 붙는 화면은 아래에서 따로) */
    static final List<String> PAGES = List.of("/", "/map.html", "/festivals.html", "/themes.html", "/community.html",
            "/news.html", "/creators.html", "/trip.html", "/notices.html", "/terms.html", "/privacy.html");

    private final SiteUrl siteUrl;
    private final JdbcTemplate jdbc;

    /**
     * 첫 화면 '/'. 원래는 스프링이 index.html 로 넘겨주는데(forward), 넘겨주면 응답이 그 자리에서 끝나
     * SiteUrlFilter 가 __SITE_URL__ 을 바꿀 수 없다. 그래서 index.html 을 직접 돌려준다 (바꾸기는 필터가 한다).
     */
    @GetMapping("/")   // produces 를 두지 않는다: Accept 가 무엇이든 HTML 로 답한다 (아래 둘도 같음)
    public ResponseEntity<byte[]> home() throws java.io.IOException {
        byte[] body = new org.springframework.core.io.ClassPathResource("static/index.html").getContentAsByteArray();
        return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8)).body(body);
    }

    @GetMapping("/robots.txt")
    public ResponseEntity<String> robots(HttpServletRequest request) {
        String body = """
                User-agent: *
                Disallow: /api/
                Disallow: /admin
                Disallow: /mypage.html
                Disallow: /agree.html
                Disallow: /post-write.html
                Disallow: /forgot-password.html
                Disallow: /oauth2/
                Disallow: /login/oauth2/

                Sitemap: %s/sitemap.xml
                """.formatted(siteUrl.of(request));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .contentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8)).body(body);
    }

    @GetMapping("/sitemap.xml")
    public ResponseEntity<String> sitemap(HttpServletRequest request) {
        String base = siteUrl.of(request);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        PAGES.forEach(p -> url(xml, base + p, null));
        // 보이는 가게, 전체 시장, 끝난 지 30일이 안 된 축제, 보이는 공지
        details(xml, base, "/store.html?id=", "SELECT id, updated_at FROM stores WHERE is_visible = 1 ORDER BY id");
        details(xml, base, "/market.html?id=", "SELECT id, updated_at FROM markets ORDER BY id");
        details(xml, base, "/festival.html?id=", "SELECT id, updated_at FROM festivals WHERE end_date >= '"
                + LocalDate.now().minusDays(30) + "' ORDER BY id");
        details(xml, base, "/notice.html?id=", "SELECT id, updated_at FROM notices WHERE visible = 1 ORDER BY id");
        xml.append("</urlset>\n");
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .contentType(new MediaType(MediaType.APPLICATION_XML, java.nio.charset.StandardCharsets.UTF_8)).body(xml.toString());
    }

    private void details(StringBuilder xml, String base, String path, String sql) {
        jdbc.query(sql, rs -> {
            java.sql.Timestamp t = rs.getTimestamp("updated_at");
            url(xml, base + path + rs.getLong("id"), t == null ? null : new Date(t.getTime()).toLocalDate());
        });
    }

    private static void url(StringBuilder xml, String loc, LocalDate lastmod) {
        xml.append("  <url><loc>").append(loc.replace("&", "&amp;")).append("</loc>");
        if (lastmod != null) xml.append("<lastmod>").append(lastmod).append("</lastmod>");
        xml.append("</url>\n");
    }
}
