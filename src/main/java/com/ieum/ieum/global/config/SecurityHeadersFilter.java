package com.ieum.ieum.global.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * 보안 머리말 (DEPLOY-01)
 *
 * - Content-Security-Policy(CSP): 화면이 불러올 수 있는 스크립트·글꼴·그림의 출처를 정해, 누가 글에 스크립트를 끼워 넣어도 돌지 않게 한다.
 *     ieum.security.csp = report-only (기본) | enforce | off
 *     처음엔 report-only 로 '막았을 것'만 /api/csp-report 로 받아 서버 로그에 남긴다 (화면은 그대로).
 *     카카오맵·관광 사진 등 실제 운영에서만 쓰는 주소가 빠지지 않았는지 한동안 로그를 본 뒤 enforce 로 바꾼다.
 * - Referrer-Policy: 다른 사이트로 나갈 때 주소 전체(검색어·글 번호)를 넘기지 않음
 * - Permissions-Policy: 위치는 이음 화면만, 카메라·마이크는 쓰지 않음
 * X-Content-Type-Options, X-Frame-Options(DENY), HSTS(HTTPS 일 때) 는 스프링 시큐리티가 이미 붙인다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String POLICY = String.join("; ",
            "default-src 'self'",
            // 카카오맵 SDK 와 SDK 가 이어서 불러오는 스크립트
            "script-src 'self' https://dapi.kakao.com https://t1.daumcdn.net",
            // Pretendard 글꼴 CSS. 카카오맵이 지도 조각에 style 속성을 쓰므로 'unsafe-inline' (스크립트가 아니라 모양만)
            "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net",
            "font-src 'self' data: https://cdn.jsdelivr.net",
            // 지도 조각·관광 사진·카카오 장소 사진 등은 https 그림이면 허용
            "img-src 'self' data: blob: https:",
            "connect-src 'self' https://dapi.kakao.com https://*.daumcdn.net",
            "worker-src 'self'",
            "manifest-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    private final String mode;

    public SecurityHeadersFilter(@Value("${ieum.security.csp:report-only}") String mode) {
        this.mode = mode == null ? "report-only" : mode.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        switch (mode) {
            // https 로 들어왔을 때만 http 그림 주소(관광 사진 등)를 https 로 올려 부르게 한다 (http 개발 화면에서는 붙이면 안 됨)
            case "enforce" -> response.setHeader("Content-Security-Policy",
                    POLICY + (request.isSecure() ? "; upgrade-insecure-requests" : "") + "; report-uri /api/csp-report");
            case "off" -> { }
            default -> response.setHeader("Content-Security-Policy-Report-Only", POLICY + "; report-uri /api/csp-report");
        }
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "geolocation=(self), camera=(), microphone=(), payment=()");
        chain.doFilter(request, response);
    }
}
