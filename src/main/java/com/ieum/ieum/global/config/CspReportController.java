package com.ieum.ieum.global.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 브라우저가 보내는 CSP 위반 보고 받기 (DEPLOY-01). 로그인 없이 받는다.
 * 'CSP 위반' WARN 로그로만 남기고 저장하지 않는다. 화면 주소는 경로만 (검색어 등 ? 뒤는 지움).
 * 아무나 보낼 수 있는 주소라 본문은 8KB 까지만 읽고, 로그는 1시간에 200줄까지만 남긴다.
 */
@Slf4j
@RestController
public class CspReportController {

    private static final int MAX = 8 * 1024;
    private static final int PER_HOUR = 200;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicLong hour = new AtomicLong();
    private final AtomicInteger count = new AtomicInteger();

    @PostMapping("/api/csp-report")
    public ResponseEntity<Void> report(HttpServletRequest request) throws IOException {
        byte[] body = readCapped(request.getInputStream());
        long h = System.currentTimeMillis() / 3_600_000L;
        if (hour.getAndSet(h) != h) count.set(0);
        if (body.length > 0 && count.incrementAndGet() <= PER_HOUR) {
            try {
                JsonNode n = json.readTree(body);
                JsonNode r = n.has("csp-report") ? n.get("csp-report") : n.isArray() && !n.isEmpty() ? n.get(0).path("body") : n;
                log.warn("CSP 위반: 막힌 것={} 규칙={} 화면={}",
                        text(r, "blocked-uri", "blockedURL"), text(r, "violated-directive", "effectiveDirective"),
                        path(text(r, "document-uri", "documentURL")));
            } catch (RuntimeException e) {
                // 모양이 이상한 보고는 버린다
            }
        }
        return ResponseEntity.noContent().build();
    }

    private static String text(JsonNode n, String a, String b) {
        JsonNode v = n.has(a) ? n.get(a) : n.get(b);
        String s = v == null || v.isNull() ? "" : v.asString();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static String path(String uri) {
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(0, q) : uri;
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        return in.readNBytes(MAX);
    }
}
