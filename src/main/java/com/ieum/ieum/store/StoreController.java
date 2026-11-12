package com.ieum.ieum.store;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 가게 API.
 *
 * GET /api/stores?lat=37.5665&lng=126.978&radius=1000&category=음식점&category=카페·간식
 *     → 반경 안의 가게 목록 (가까운 순)
 * GET /api/stores/{id}
 *     → 가게 상세
 */
@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @GetMapping
    public List<StoreResponses.Summary> nearby(@RequestParam double lat,
                                               @RequestParam double lng,
                                               @RequestParam(required = false) Integer radius,
                                               @RequestParam(name = "category", required = false) List<String> categories) {
        return storeService.findNearby(lat, lng, radius, categories);
    }

    @GetMapping("/{id}")
    public StoreResponses.Detail detail(@PathVariable Long id) {
        return storeService.findDetail(id);
    }
}
