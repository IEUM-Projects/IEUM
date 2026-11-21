package com.ieum.ieum.favorite;

import com.ieum.ieum.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 찜한 곳 (FAV-01). 모두 로그인 필요.
 *
 * GET    /api/favorites/{store|market}/{id}   찜했는지 → {saved}
 * PUT    /api/favorites/{store|market}/{id}   찜하기 (몇 번 해도 같음) → {saved: true}
 * DELETE /api/favorites/{store|market}/{id}   찜 풀기 (몇 번 해도 같음) → {saved: false}
 * GET    /api/users/me/favorites              내 정보의 '찜한 곳' (최근 순)
 */
@RestController
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @GetMapping("/api/favorites/{type}/{id}")
    public FavoriteService.Saved state(@AuthenticationPrincipal AuthUser user, @PathVariable String type, @PathVariable long id) {
        return favoriteService.state(user.id(), type, id);
    }

    @PutMapping("/api/favorites/{type}/{id}")
    public FavoriteService.Saved save(@AuthenticationPrincipal AuthUser user, @PathVariable String type, @PathVariable long id) {
        return favoriteService.save(user.id(), type, id);
    }

    @DeleteMapping("/api/favorites/{type}/{id}")
    public FavoriteService.Saved remove(@AuthenticationPrincipal AuthUser user, @PathVariable String type, @PathVariable long id) {
        return favoriteService.remove(user.id(), type, id);
    }

    @GetMapping("/api/users/me/favorites")
    public List<FavoriteService.Item> mine(@AuthenticationPrincipal AuthUser user) {
        return favoriteService.list(user.id());
    }
}
