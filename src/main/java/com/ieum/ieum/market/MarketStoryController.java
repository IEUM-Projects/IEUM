package com.ieum.ieum.market;

import com.ieum.ieum.admin.AdminGuard;
import com.ieum.ieum.global.security.AuthUser;
import com.ieum.ieum.market.MarketStoryService.MarketHit;
import com.ieum.ieum.market.MarketStoryService.Story;
import com.ieum.ieum.market.MarketStoryService.StoryPage;
import com.ieum.ieum.market.MarketStoryService.StoryRequest;
import com.ieum.ieum.market.MarketStoryService.TourInfo;
import com.ieum.ieum.market.MarketStoryService.TourResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 시장 이야기 (MKT-02)
 *
 * 누구나:   GET /api/markets/{id}/story          공개한 이야기 + 관광공사 소개글 (처음 볼 때 불러옴)
 * 관리자:   GET /api/admin/stories?status=ALL|DRAFT|PUBLISHED|HIDDEN&page=0
 *           GET /api/admin/stories/markets?q=시장이름   이야기를 쓸 시장 찾기
 *           GET /api/admin/stories/{marketId}          초안·숨김까지 + 관광공사 소개글(참고용)
 *           PUT /api/admin/stories/{marketId}          {"body", "sources": [{"title", "url"}]} 저장 (새 글은 초안)
 *           POST /api/admin/stories/{marketId}/publish  공개
 *           POST /api/admin/stories/{marketId}/hide     숨김
 */
@RestController
@RequiredArgsConstructor
public class MarketStoryController {

    private final MarketStoryService service;
    private final AdminGuard guard;

    public record PublicStory(Story story, TourInfo tour, boolean tourAvailable) {
    }

    @GetMapping("/api/markets/{id}/story")
    public PublicStory story(@PathVariable long id) {
        service.market(id);
        TourResult t = service.tour(id);
        return new PublicStory(service.story(id, true).orElse(null), t.info().orElse(null), t.available());
    }

    public record AdminStory(long marketId, String marketName, String address, Story story, TourInfo tour, boolean tourAvailable) {
    }

    @GetMapping("/api/admin/stories")
    public StoryPage list(@AuthenticationPrincipal AuthUser authUser, @RequestParam(defaultValue = "ALL") String status,
                          @RequestParam(defaultValue = "0") int page) {
        guard.requireAdmin(authUser);
        return service.list(status, page);
    }

    @GetMapping("/api/admin/stories/markets")
    public List<MarketHit> markets(@AuthenticationPrincipal AuthUser authUser, @RequestParam(defaultValue = "") String q) {
        guard.requireAdmin(authUser);
        return service.searchMarkets(q);
    }

    @GetMapping("/api/admin/stories/{marketId}")
    public AdminStory get(@AuthenticationPrincipal AuthUser authUser, @PathVariable long marketId) {
        guard.requireAdmin(authUser);
        return admin(marketId);
    }

    @PutMapping("/api/admin/stories/{marketId}")
    public AdminStory save(@AuthenticationPrincipal AuthUser authUser, @PathVariable long marketId,
                           @RequestBody(required = false) StoryRequest request) {
        service.save(guard.requireAdmin(authUser).getId(), marketId, request);
        return admin(marketId);
    }

    @PostMapping("/api/admin/stories/{marketId}/publish")
    public AdminStory publish(@AuthenticationPrincipal AuthUser authUser, @PathVariable long marketId) {
        service.setStatus(guard.requireAdmin(authUser).getId(), marketId, "PUBLISHED");
        return admin(marketId);
    }

    @PostMapping("/api/admin/stories/{marketId}/hide")
    public AdminStory hide(@AuthenticationPrincipal AuthUser authUser, @PathVariable long marketId) {
        service.setStatus(guard.requireAdmin(authUser).getId(), marketId, "HIDDEN");
        return admin(marketId);
    }

    private AdminStory admin(long marketId) {
        var m = service.market(marketId);
        TourResult t = service.tour(marketId);
        return new AdminStory(marketId, (String) m.get("name"), (String) m.get("road_address"),
                service.story(marketId, false).orElse(null), t.info().orElse(null), t.available());
    }
}
