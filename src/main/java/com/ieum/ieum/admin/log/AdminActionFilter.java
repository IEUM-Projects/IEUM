package com.ieum.ieum.admin.log;

import com.ieum.ieum.global.security.AuthUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Set;

/**
 * 관리자 작업 자동 기록 (ADM-03).
 * '/api/admin/...' 로 들어온 바꾸기 요청(POST·PUT·PATCH·DELETE)이 성공(2xx)하면 AdminActionService 로 한 줄 남긴다.
 * 컨트롤러마다 기록 코드를 넣지 않아도, 새 관리자 API 가 생겨도 빠짐없이 남는다 (이름은 AdminActionRoutes 에서).
 *
 * - 요청·응답 본문은 64KB 까지만 들여다본다 (설명 칸을 꺼내려고). 파일 올리기(multipart)는 본문을 보지 않는다.
 * - 기록에 실패해도 관리자 요청은 그대로 성공한다 (로그만 남김).
 * - 스프링 시큐리티 뒤에서 돌아서, 로그인한 관리자 번호를 알 수 있다.
 */
@Slf4j
@Component
public class AdminActionFilter extends OncePerRequestFilter {

    private static final Set<String> WRITE = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final int LIMIT = 64 * 1024;

    private final AdminActionService service;
    private final JsonMapper json = JsonMapper.builder().build();

    public AdminActionFilter(AdminActionService service) {
        this.service = service;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/admin/") || !WRITE.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean multipart = request.getContentType() != null && request.getContentType().toLowerCase().startsWith("multipart/");
        HttpServletRequest req = multipart ? request : new ContentCachingRequestWrapper(request, LIMIT);
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
        Long adminId = null;
        try {
            chain.doFilter(req, res);
            adminId = currentAdmin();
        } finally {
            try {
                if (adminId != null && res.getStatus() >= 200 && res.getStatus() < 300) {
                    String path = request.getRequestURI().substring(request.getContextPath().length());
                    JsonNode body = req instanceof ContentCachingRequestWrapper w ? parse(w.getContentAsByteArray()) : null;
                    JsonNode out = res.getContentSize() <= LIMIT ? parse(res.getContentAsByteArray()) : null;
                    service.record(adminId, AdminActionRoutes.resolve(request.getMethod(), path, body, out));
                }
            } catch (RuntimeException e) {
                log.warn("관리자 작업 기록을 남기지 못했어요: {} {} ({})", request.getMethod(), request.getRequestURI(), e.getMessage());
            }
            res.copyBodyToResponse();
        }
    }

    private static Long currentAdmin() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof AuthUser u ? u.id() : null;
    }

    private JsonNode parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            JsonNode n = json.readTree(bytes);
            return n != null && n.isObject() ? n : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
