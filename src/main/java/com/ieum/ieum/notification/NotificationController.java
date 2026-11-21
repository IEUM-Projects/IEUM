package com.ieum.ieum.notification;

import com.ieum.ieum.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 알림 (NOTI-01). 모두 로그인 필요.
 *
 * GET  /api/notifications?page=0             내 알림 (최근 순 20개씩) → {items, unread, hasMore}
 * GET  /api/notifications/unread-count       안 읽은 수 → {unread} (위쪽 종 아이콘)
 * POST /api/notifications/{id}/read          하나 읽음 → 204
 * POST /api/notifications/read-all           모두 읽음 → 204
 */
@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/api/notifications")
    public NotificationService.Page list(@AuthenticationPrincipal AuthUser user, @RequestParam(defaultValue = "0") int page) {
        return notificationService.list(user.id(), page);
    }

    @GetMapping("/api/notifications/unread-count")
    public Map<String, Integer> unread(@AuthenticationPrincipal AuthUser user) {
        return Map.of("unread", notificationService.unread(user.id()));
    }

    @PostMapping("/api/notifications/{id}/read")
    public ResponseEntity<Void> read(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        notificationService.read(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/notifications/read-all")
    public ResponseEntity<Void> readAll(@AuthenticationPrincipal AuthUser user) {
        notificationService.readAll(user.id());
        return ResponseEntity.noContent().build();
    }
}
