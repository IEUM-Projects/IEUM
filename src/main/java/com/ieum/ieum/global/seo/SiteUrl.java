package com.ieum.ieum.global.seo;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 사이트 주소 (SEO-01). 링크 미리보기(og:image·og:url)와 sitemap.xml 은 https://… 로 시작하는 전체 주소여야 한다.
 *
 * ieum.site.base-url 이 있으면 그 값 (운영: https://도메인), 없으면 지금 요청의 주소 (개발: http://localhost:8080).
 * 운영에서는 꼭 설정한다: 앞단 프록시를 거치면 요청 주소가 http·내부 주소로 보일 수 있다.
 */
@Component
public class SiteUrl {

    private final String configured;

    public SiteUrl(@Value("${ieum.site.base-url:}") String baseUrl) {
        String v = baseUrl == null ? "" : baseUrl.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        this.configured = v;
    }

    public String of(HttpServletRequest request) {
        if (!configured.isEmpty()) return configured;
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port) + request.getContextPath();
    }
}
