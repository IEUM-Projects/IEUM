package com.ieum.ieum.tour;

import com.ieum.ieum.festival.KakaoLocalClient;
import com.ieum.ieum.festival.KakaoLocalClient.Place;
import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 걷기 코스 출발점 찾기 (TOUR-01). 누구나 조회.
 *
 * GET /api/places/search?q=경주역   카카오 장소 검색 (최대 10곳, 대한민국 안만)
 * 카카오 키는 서버에만 있다. 결과는 저장하지 않고 그때그때 카카오에 묻는다 (카카오 로컬 API 이용 조건, DATA-05).
 * 카카오가 안 되면 200 + available=false (화면은 '현재 위치'로 시작하라고 안내).
 */
@Slf4j
@RestController
@RequestMapping("/api/places")
@RequiredArgsConstructor
public class PlaceSearchController {

    static final int MIN_LENGTH = 2;
    static final int MAX_LENGTH = 30;
    static final int SIZE = 10;

    private final KakaoLocalClient kakao;

    public record SearchResponse(String query, List<Place> places, boolean available, String source) {
    }

    @GetMapping("/search")
    public SearchResponse search(@RequestParam(defaultValue = "") String q) {
        String query = q.strip().replaceAll("\\s+", " ");
        if (query.length() < MIN_LENGTH || query.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("검색어는 2~30자로 넣어 주세요."));
        }
        if (!kakao.hasKey()) return new SearchResponse(query, List.of(), false, "카카오 장소 검색");
        List<Place> places;
        try {
            places = kakao.keyword(query, 15).stream()
                    .filter(p -> p.lat() >= 33 && p.lat() <= 39 && p.lng() >= 124 && p.lng() <= 132)
                    .limit(SIZE).toList();
        } catch (KakaoLocalClient.KakaoException e) {
            log.warn("장소 검색 실패: {}", e.getMessage());
            return new SearchResponse(query, List.of(), false, "카카오 장소 검색");
        }
        return new SearchResponse(query, places, true, "카카오 장소 검색");
    }
}
