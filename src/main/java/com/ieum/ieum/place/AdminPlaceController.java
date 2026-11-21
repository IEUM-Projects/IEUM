package com.ieum.ieum.place;

import com.ieum.ieum.admin.AdminGuard;
import com.ieum.ieum.festival.KakaoLocalClient;
import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.global.security.AuthUser;
import com.ieum.ieum.place.AdminPlaceService.Candidates;
import com.ieum.ieum.place.AdminPlaceService.PlacePage;
import com.ieum.ieum.place.AdminPlaceService.Row;
import com.ieum.ieum.region.GeoService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 장소 정보 (DATA-05) - 화면: /admin-places.html
 *
 * GET    /api/admin/places?tab=REVIEW|NO_COORD|NO_CATEGORY|LINKED|NO_PLACE&type=CENTURY|CREATOR&q=&page=0
 * GET    /api/admin/places/{id}/candidates?q=      카카오맵 후보 (그때그때 검색, 저장 안 함)
 * PUT    /api/admin/places/{id}/place  {"placeId"} 카카오맵 장소 잇기 (장소 ID만 저장)
 * DELETE /api/admin/places/{id}/place               연결 풀기 (확인 필요로)
 * POST   /api/admin/places/{id}/no-place            카카오맵에 없음
 * PUT    /api/admin/places/{id}/category {"category"}  분류 정하기 (빈 값 = 분류 없음)
 * PUT    /api/admin/places/{id}/address  {"roadAddress"} 주소 고치기 → 브이월드로 좌표·지역 다시 찾기
 * GET    /api/admin/places/jobs                     좌표 찾기·자동 연결 진행 상황
 * POST   /api/admin/places/jobs/geocode             좌표 다시 찾기 (못 찾았던 곳 포함)
 * POST   /api/admin/places/jobs/link                카카오맵 자동 연결 (아직 안 찾은 곳)
 */
@RestController
@RequestMapping("/api/admin/places")
@RequiredArgsConstructor
public class AdminPlaceController {

    private final AdminPlaceService service;
    private final StoreGeocodeService geocode;
    private final PlaceLinkService link;
    private final GeoService geo;
    private final KakaoLocalClient kakao;
    private final AdminGuard guard;

    @GetMapping
    public PlacePage list(@AuthenticationPrincipal AuthUser authUser, @RequestParam(defaultValue = "REVIEW") String tab,
                          @RequestParam(defaultValue = "") String type, @RequestParam(defaultValue = "") String q,
                          @RequestParam(defaultValue = "0") int page) {
        guard.requireAdmin(authUser);
        return service.list(tab, type, q, page);
    }

    @GetMapping("/{id}/candidates")
    public Candidates candidates(@AuthenticationPrincipal AuthUser authUser, @PathVariable long id,
                                 @RequestParam(defaultValue = "") String q) {
        guard.requireAdmin(authUser);
        return service.candidates(id, q);
    }

    public record PlaceRequest(String placeId) {
    }

    @PutMapping("/{id}/place")
    public Row link(@AuthenticationPrincipal AuthUser authUser, @PathVariable long id,
                    @RequestBody(required = false) PlaceRequest request) {
        long admin = guard.requireAdmin(authUser).getId();
        return service.link(admin, id, request == null ? null : request.placeId());
    }

    @DeleteMapping("/{id}/place")
    public Row unlink(@AuthenticationPrincipal AuthUser authUser, @PathVariable long id) {
        return service.unlink(guard.requireAdmin(authUser).getId(), id, false);
    }

    @PostMapping("/{id}/no-place")
    public Row noPlace(@AuthenticationPrincipal AuthUser authUser, @PathVariable long id) {
        return service.unlink(guard.requireAdmin(authUser).getId(), id, true);
    }

    public record CategoryRequest(String category) {
    }

    @PutMapping("/{id}/category")
    public Row category(@AuthenticationPrincipal AuthUser authUser, @PathVariable long id,
                        @RequestBody(required = false) CategoryRequest request) {
        long admin = guard.requireAdmin(authUser).getId();
        return service.category(admin, id, request == null ? null : request.category());
    }

    public record AddressRequest(String roadAddress) {
    }

    @PutMapping("/{id}/address")
    public Row address(@AuthenticationPrincipal AuthUser authUser, @PathVariable long id,
                       @RequestBody(required = false) AddressRequest request) {
        long admin = guard.requireAdmin(authUser).getId();
        return service.address(admin, id, request == null ? null : request.roadAddress());
    }

    public record Jobs(PlaceJob.View geocode, PlaceJob.View link, boolean vworldAvailable, boolean kakaoAvailable) {
    }

    @GetMapping("/jobs")
    public Jobs jobs(@AuthenticationPrincipal AuthUser authUser) {
        guard.requireAdmin(authUser);
        return jobs();
    }

    @PostMapping("/jobs/geocode")
    public Jobs startGeocode(@AuthenticationPrincipal AuthUser authUser) {
        guard.requireAdmin(authUser);
        if (geocode.start(true, false).isEmpty()) throw busy();
        return jobs();
    }

    @PostMapping("/jobs/link")
    public Jobs startLink(@AuthenticationPrincipal AuthUser authUser) {
        guard.requireAdmin(authUser);
        if (geocode.isRunning() || link.start().isEmpty()) throw busy();
        return jobs();
    }

    private Jobs jobs() {
        return new Jobs(geocode.current().orElse(null), link.current().orElse(null), geo.available(), kakao.hasKey());
    }

    private static BusinessException busy() {
        return new BusinessException(ErrorCode.PLACE_JOB_RUNNING);
    }
}
