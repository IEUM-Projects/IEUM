package com.ieum.ieum.theme;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.theme.ThemeDtos.ThemePage;
import com.ieum.ieum.theme.ThemeDtos.ThemeSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 테마로 걷기 (누구나, THM-01)
 * GET /api/themes?lat&lng                         테마 3가지 요약 (전국 수, 30km 안 수, 오늘 장 서는 곳 수)
 * GET /api/themes/alleys?lat&lng&page             백년가게 골목 (20개씩, 위치가 있으면 가까운 순)
 * GET /api/themes/market-stores?lat&lng&page      시장 안 백년가게
 * GET /api/themes/fair-markets?lat&lng&page&today=true   5일장 (today=true: 오늘 장 서는 곳만)
 * 목록은 &radius=auto|2000|10000|30000|all 로 반경을 고른다 (THM-02, 위치가 있을 때만. 없으면 전국)
 *   auto(기본): 2km → 10km → 30km 순으로 넓혀 처음 있는 반경. 30km 안에도 없으면 전국
 */
@RestController
@RequestMapping("/api/themes")
@RequiredArgsConstructor
public class ThemeController {

    private final ThemeService service;

    @GetMapping
    public List<ThemeSummary> summaries(@RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        return service.summaries(where(lat, lng));
    }

    @GetMapping("/{id}")
    public ThemePage<?> theme(@PathVariable String id,
                              @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "false") boolean today,
                              @RequestParam(defaultValue = "auto") String radius) {
        ThemeService.Where where = where(lat, lng);
        ThemeService.Radius r = radius(radius);
        return switch (id) {
            case "alleys" -> service.alleys(where, page, r);
            case "market-stores" -> service.marketStores(where, page, r);
            case "fair-markets" -> service.fairMarkets(where, page, today, r);
            default -> throw new BusinessException(ErrorCode.NOT_FOUND);
        };
    }

    /** 정해진 반경만 받는다 (그 밖의 값은 auto) */
    private static ThemeService.Radius radius(String value) {
        if ("all".equals(value)) return ThemeService.Radius.ALL;
        for (int r : ThemeService.RADII) if (String.valueOf(r).equals(value)) return ThemeService.Radius.of(r);
        return ThemeService.Radius.AUTO;
    }

    /** 대한민국 범위 안의 좌표만 쓴다 (아니면 위치 없이) */
    private static ThemeService.Where where(Double lat, Double lng) {
        if (lat == null || lng == null || lat < 33 || lat > 39 || lng < 124 || lng > 132) {
            return new ThemeService.Where(null, null);
        }
        return new ThemeService.Where(lat, lng);
    }
}
