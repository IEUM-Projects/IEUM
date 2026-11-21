package com.ieum.ieum.global.seo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 화면(HTML) 파일 안의 __SITE_URL__ 을 사이트 주소로 바꿔서 보낸다 (SEO-01).
 * 카카오톡·검색 엔진은 자바스크립트를 실행하지 않고 HTML 의 og 태그만 읽으므로,
 * 미리보기 이미지 주소(og:image)는 처음 받는 HTML 에 전체 주소로 들어 있어야 한다.
 * 도메인을 코드에 박지 않으려고 HTML 에는 자리 표시만 두고 여기서 채운다.
 * 상세 화면(가게·시장·축제·글·공지)은 주소의 id 로 제목·설명·og 태그도 채운다 (SEO-02, DetailMeta).
 */
@Component
@RequiredArgsConstructor
public class SiteUrlFilter extends OncePerRequestFilter {

    static final String TOKEN = "__SITE_URL__";

    private final SiteUrl siteUrl;
    private final DetailMeta detailMeta;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/") || path.endsWith(".html"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, wrapper);
        String type = wrapper.getContentType();
        byte[] body = wrapper.getContentAsByteArray();
        if (body.length == 0 || type == null || !type.startsWith("text/html")) {
            wrapper.copyBodyToResponse();
            return;
        }
        String html = new String(body, StandardCharsets.UTF_8);
        if (!html.contains(TOKEN)) {
            wrapper.copyBodyToResponse();
            return;
        }
        String site = siteUrl.of(request);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String page = html.replace(TOKEN, site);
        try {
            String id = request.getParameter("id");
            var meta = detailMeta.find(path, id);
            if (meta.isPresent()) page = DetailMeta.apply(page, meta.get(), site, site + path + "?id=" + id);
        } catch (RuntimeException e) {
            // 미리보기를 못 채워도 화면은 그대로 보낸다 (DB가 잠깐 안 될 때 등)
        }
        byte[] out = page.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(out.length);
        response.getOutputStream().write(out);
    }
}
