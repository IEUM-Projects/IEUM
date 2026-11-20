package com.ieum.ieum.community;

import com.ieum.ieum.admin.AdminGuard;
import com.ieum.ieum.community.CommunityAdminService.Target;
import com.ieum.ieum.community.CommunityAdminService.TargetPage;
import com.ieum.ieum.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 관리자 게시판 관리 (COM-01) - 화면: /admin-community.html
 *
 * GET  /api/admin/community?tab=REPORTED|HIDDEN|RECOMMEND
 * POST /api/admin/community/{POST|COMMENT}/{id}/hide     {"reason", "temp": false}  가리기 (temp=true: 30일 임시 숨김)
 * POST /api/admin/community/{POST|COMMENT}/{id}/restore  다시 보이게 (접수 신고는 문제없음으로)
 * PUT  /api/admin/community/posts/{id}/recommend         {"status":"ADDED|EXISTS|HOLD|PENDING","note"}
 * POST /api/admin/users/{id}/suspend                     {"suspend": true|false}  작성 정지 / 풀기
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class CommunityAdminController {

    private final CommunityAdminService service;
    private final AdminGuard guard;

    @GetMapping("/community")
    public TargetPage list(@AuthenticationPrincipal AuthUser authUser, @RequestParam(defaultValue = "REPORTED") String tab) {
        guard.requireAdmin(authUser);
        return service.list(tab);
    }

    public record HideRequest(String reason, Boolean temp) {
    }

    @PostMapping("/community/{type}/{id}/hide")
    public Target hide(@AuthenticationPrincipal AuthUser authUser, @PathVariable String type, @PathVariable long id,
                       @RequestBody(required = false) HideRequest req) {
        long admin = guard.requireAdmin(authUser).getId();
        return service.hide(admin, type, id, req != null && Boolean.TRUE.equals(req.temp()), req == null ? null : req.reason());
    }

    @PostMapping("/community/{type}/{id}/restore")
    public Target restore(@AuthenticationPrincipal AuthUser authUser, @PathVariable String type, @PathVariable long id) {
        long admin = guard.requireAdmin(authUser).getId();
        return service.restore(admin, type, id);
    }

    public record RecommendRequest(String status, String note) {
    }

    @PutMapping("/community/posts/{id}/recommend")
    public Target recommend(@AuthenticationPrincipal AuthUser authUser, @PathVariable long id,
                            @RequestBody(required = false) RecommendRequest req) {
        guard.requireAdmin(authUser);
        return service.recommend(id, req == null ? null : req.status(), req == null ? null : req.note());
    }

    public record SuspendRequest(Boolean suspend) {
    }

    @PostMapping("/users/{id}/suspend")
    public Map<String, String> suspend(@AuthenticationPrincipal AuthUser authUser, @PathVariable long id,
                                       @RequestBody(required = false) SuspendRequest req) {
        guard.requireAdmin(authUser);
        return Map.of("status", service.suspend(id, req == null || !Boolean.FALSE.equals(req.suspend())));
    }
}
