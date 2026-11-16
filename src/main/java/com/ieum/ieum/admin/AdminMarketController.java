package com.ieum.ieum.admin;

import com.ieum.ieum.admin.AdminMarketService.LinkPage;
import com.ieum.ieum.admin.AdminMarketService.LinkRequest;
import com.ieum.ieum.admin.AdminMarketService.LinkRow;
import com.ieum.ieum.global.security.AuthUser;
import com.ieum.ieum.market.MarketFacility.LinkStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 전통시장 시설 정보 연결 확인 (관리자, DATA-01)
 *
 * GET  /api/admin/market-links?status=PENDING&page=0   상태별 목록 (20개씩) + 상태별 수
 * POST /api/admin/market-links/{code}                   {"action":"LINK"} / {"action":"LINK","marketId":123}
 *                                                       {"action":"IGNORE"} / {"action":"RESET"}
 */
@RestController
@RequestMapping("/api/admin/market-links")
@RequiredArgsConstructor
public class AdminMarketController {

    private final AdminGuard guard;
    private final AdminMarketService service;

    @GetMapping
    public LinkPage list(@AuthenticationPrincipal AuthUser authUser,
                         @RequestParam(defaultValue = "PENDING") LinkStatus status,
                         @RequestParam(defaultValue = "0") int page) {
        guard.requireAdmin(authUser);
        return service.list(status, page);
    }

    @PostMapping("/{code}")
    public LinkRow act(@AuthenticationPrincipal AuthUser authUser, @PathVariable String code,
                       @Valid @RequestBody LinkRequest request) {
        return service.act(guard.requireAdmin(authUser), code, request);
    }
}
