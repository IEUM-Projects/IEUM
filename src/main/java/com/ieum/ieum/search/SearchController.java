package com.ieum.ieum.search;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.search.SearchDtos.PageResult;
import com.ieum.ieum.search.SearchDtos.SearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 통합 검색 (누구나)
 * GET /api/search?q=전주 국밥&lat=37.5&lng=127.0        지역 + 종류마다 앞의 5개와 전체 수
 * GET /api/search/stores?q=...&page=0                   백년가게 더 보기 (20개씩). markets, festivals 도 같음
 * lat, lng(기준 위치)는 선택: 보내면 같은 순위 안에서 가까운 순, 거리도 함께
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private static final Set<String> TYPES = Set.of("stores", "markets", "festivals");

    private final SearchService service;

    @GetMapping
    public SearchResult search(@RequestParam(required = false) String q,
                               @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        return service.search(SearchQuery.parse(q), where(lat, lng));
    }

    @GetMapping("/{type}")
    public PageResult<?> page(@PathVariable String type, @RequestParam(required = false) String q,
                              @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng,
                              @RequestParam(defaultValue = "0") int page) {
        if (!TYPES.contains(type)) throw new BusinessException(ErrorCode.NOT_FOUND);
        return service.page(type, SearchQuery.parse(q), where(lat, lng), page);
    }

    /** 대한민국 범위 안의 좌표만 쓴다 (아니면 거리 없이 이름 순) */
    private static SearchService.Where where(Double lat, Double lng) {
        if (lat == null || lng == null || lat < 33 || lat > 39 || lng < 124 || lng > 132) {
            return new SearchService.Where(null, null);
        }
        return new SearchService.Where(lat, lng);
    }
}
