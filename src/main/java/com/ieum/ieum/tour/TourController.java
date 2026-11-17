package com.ieum.ieum.tour;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.market.Market;
import com.ieum.ieum.market.MarketRepository;
import com.ieum.ieum.store.StoreRepository;
import com.ieum.ieum.tour.TourService.Nearby;
import com.ieum.ieum.tour.TourService.Spot;
import lombok.RequiredArgsConstructor;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 주변 관광지와 걷기 코스 (TOUR-01). 누구나 조회.
 *
 * GET /api/tour/nearby?lat&lng&radius=2000&types=12,14,28,25   주변 관광 정보 (가까운 순, 최대 2km, 20곳)
 * GET /api/tour/course?marketId=1                         시장에서 시작하는 코스 재료 (출발점, 주변 백년가게, 주변 관광지)
 * GET /api/tour/course?lat&lng&label=서울 중구              고른 위치에서 시작
 * GET /api/tour/walk?p=37.56,126.97&p=37.55,126.98...   구간마다 실제 도보 경로 (TMAP, 점 2~7개)
 * 순서는 화면이 정한다 (관광지를 빼고 넣을 때마다 바로 다시 계산하려고).
 */
@RestController
@RequestMapping("/api/tour")
@RequiredArgsConstructor
public class TourController {

    static final int STORE_RADIUS = 1500;
    static final int SPOT_RADIUS = 2000;

    private final TourService tourService;
    private final MarketRepository marketRepository;
    private final StoreRepository storeRepository;
    private final WalkRouteService walkRouteService;

    public record WalkResponse(List<WalkRouteService.Leg> legs, boolean enabled, String source) {
    }

    public record NearbyResponse(List<Spot> spots, boolean available, String source) {
    }

    public record Start(String kind, Long id, String name, double latitude, double longitude) {
    }

    public record CourseStore(Long id, String name, String category, double latitude, double longitude, long distanceM, boolean onnuri) {
    }

    public record CourseResponse(Start start, List<CourseStore> stores, List<Spot> spots, boolean tourAvailable,
                                 int storeRadiusM, int spotRadiusM, String source) {
    }

    private static final String SOURCE = "한국관광공사 국문 관광정보";

    @GetMapping("/nearby")
    public NearbyResponse nearby(@RequestParam double lat, @RequestParam double lng,
                                 @RequestParam(defaultValue = "2000") int radius,
                                 @RequestParam(required = false) List<Integer> types) {
        checkKorea(lat, lng);
        List<Integer> t = types == null ? List.of() : types.stream().filter(TourService.TYPES::containsKey).distinct().toList();
        Nearby n = tourService.nearby(lat, lng, radius, t.isEmpty() ? TourService.ALL_TYPES : t, 20);
        return new NearbyResponse(n.spots(), n.available(), SOURCE);
    }

    @GetMapping("/course")
    public CourseResponse course(@RequestParam(required = false) Long marketId,
                                 @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng,
                                 @RequestParam(required = false) String label) {
        Start start;
        if (marketId != null) {
            Market m = marketRepository.findById(marketId).orElseThrow(() -> new BusinessException(ErrorCode.MARKET_NOT_FOUND));
            if (m.getLatitude() == null || m.getLongitude() == null) throw new BusinessException(ErrorCode.MARKET_NOT_FOUND);
            start = new Start("market", m.getId(), m.getName(), m.getLatitude().doubleValue(), m.getLongitude().doubleValue());
        } else if (lat != null && lng != null) {
            checkKorea(lat, lng);
            String name = label == null || label.isBlank() ? "고른 위치" : label.strip();
            start = new Start("place", null, name.length() > 30 ? name.substring(0, 30) : name, lat, lng);
        } else {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("시장 번호나 위치(lat, lng)를 넣어 주세요."));
        }
        List<CourseStore> stores = storeRepository.findNearby(start.latitude(), start.longitude(), STORE_RADIUS, true, List.of(""), 30)
                .stream().map(s -> new CourseStore(s.getId(), s.getName(), s.getCategory(), s.getLatitude().doubleValue(),
                        s.getLongitude().doubleValue(), s.getDistanceM() == null ? 0 : s.getDistanceM().longValue(),
                        s.getOnnuri() != null && s.getOnnuri() == 1))
                .toList();
        Nearby spots = tourService.nearby(start.latitude(), start.longitude(), SPOT_RADIUS, TourService.ALL_TYPES, 40);
        return new CourseResponse(start, stores, spots.spots(), spots.available(), STORE_RADIUS, SPOT_RADIUS, SOURCE);
    }

    @GetMapping("/walk")
    public WalkResponse walk(@RequestParam MultiValueMap<String, String> params) {
        // List<String>으로 받으면 "위도,경도" 하나짜리가 쉼표에서 둘로 쪼개져서 그대로 받는다
        List<String> p = params.getOrDefault("p", List.of());
        if (p.size() < 2 || p.size() > WalkRouteService.MAX_LEGS + 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("점은 2~7개를 넣어 주세요."));
        }
        List<double[]> points = p.stream().map(TourController::point).toList();
        return new WalkResponse(walkRouteService.legs(points), walkRouteService.enabled(), "TMAP 보행자 경로");
    }

    private static double[] point(String text) {
        String[] parts = text.split(",");
        try {
            if (parts.length != 2) throw new NumberFormatException();
            double lat = Double.parseDouble(parts[0].strip());
            double lng = Double.parseDouble(parts[1].strip());
            checkKorea(lat, lng);
            return new double[]{lat, lng};
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("점은 '위도,경도'로 넣어 주세요."));
        }
    }

    private static void checkKorea(double lat, double lng) {
        if (lat < 33 || lat > 39 || lng < 124 || lng > 132) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("대한민국 안의 위치를 넣어 주세요."));
        }
    }
}
