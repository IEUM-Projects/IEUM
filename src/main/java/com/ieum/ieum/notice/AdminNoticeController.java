package com.ieum.ieum.notice;

import com.ieum.ieum.admin.AdminGuard;
import com.ieum.ieum.global.security.AuthUser;
import com.ieum.ieum.notice.NoticeDtos.NoticeDetail;
import com.ieum.ieum.notice.NoticeDtos.NoticePage;
import com.ieum.ieum.notice.NoticeDtos.NoticeRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공지사항 관리 (관리자만)
 * GET  /api/admin/notices?page=0    숨긴 공지까지
 * GET  /api/admin/notices/{id}
 * POST /api/admin/notices           쓰기 (201)  {"title","content","pinned":false,"visible":true}
 * PUT  /api/admin/notices/{id}      고치기 (고정·숨김도 여기서)
 * 지우기는 없다. 내릴 때는 visible=false (숨김)
 */
@RestController
@RequestMapping("/api/admin/notices")
@RequiredArgsConstructor
public class AdminNoticeController {

    private final AdminGuard guard;
    private final NoticeService service;

    @GetMapping
    public NoticePage list(@AuthenticationPrincipal AuthUser authUser, @RequestParam(defaultValue = "0") int page) {
        guard.requireAdmin(authUser);
        return service.listForAdmin(page, 20);
    }

    @GetMapping("/{id}")
    public NoticeDetail get(@AuthenticationPrincipal AuthUser authUser, @PathVariable Long id) {
        guard.requireAdmin(authUser);
        return service.getForAdmin(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoticeDetail write(@AuthenticationPrincipal AuthUser authUser, @Valid @RequestBody NoticeRequest request) {
        return service.write(guard.requireAdmin(authUser), request);
    }

    @PutMapping("/{id}")
    public NoticeDetail edit(@AuthenticationPrincipal AuthUser authUser, @PathVariable Long id,
                             @Valid @RequestBody NoticeRequest request) {
        return service.edit(guard.requireAdmin(authUser), id, request);
    }
}
