package com.ieum.ieum.admin;

import com.ieum.ieum.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 회원 관리 (ADM-02). /api/admin/** 는 관리자만 (SecurityConfig).
 *
 * GET    /api/admin/users?q=&status=&page=0    목록·검색 (30명씩, 최근 가입 순). status: ACTIVE·SUSPENDED·WITHDRAWN·BANNED·ADMIN
 * GET    /api/admin/users/{id}                 상세
 * POST   /api/admin/users/{id}/withdraw        강제 탈퇴 {"reason"} (되돌릴 수 없음)
 * DELETE /api/admin/users/{id}/ban             재가입 허용
 * (작성 정지·풀기: POST /api/admin/users/{id}/suspend, 동네 이야기 관리와 같은 API)
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService service;

    public record WithdrawRequest(String reason) {
    }

    @GetMapping
    public AdminUserService.Page list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String status,
                                      @RequestParam(defaultValue = "0") int page) {
        return service.list(q, status, page);
    }

    @GetMapping("/{id}")
    public AdminUserService.Detail detail(@PathVariable long id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/withdraw")
    public AdminUserService.Detail withdraw(@AuthenticationPrincipal AuthUser admin, @PathVariable long id,
                                            @RequestBody(required = false) WithdrawRequest req) {
        return service.forceWithdraw(admin.id(), id, req == null ? null : req.reason());
    }

    @DeleteMapping("/{id}/ban")
    public AdminUserService.Detail allowRejoin(@AuthenticationPrincipal AuthUser admin, @PathVariable long id) {
        return service.allowRejoin(admin.id(), id);
    }
}
