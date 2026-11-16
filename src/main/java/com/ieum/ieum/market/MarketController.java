package com.ieum.ieum.market;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 전통시장 API (누구나 조회 가능)
 *
 * GET /api/markets?lat=37.56&lng=126.97&radius=1000
 *     → 반경 안의 전통시장 (가까운 순)
 * GET /api/markets/{id}
 *     → 시장 상세 + 반경 300m 안 백년가게 + 같은 건물의 다른 시장
 *
 * 장날 계산(오늘 장이 서는지, 다음 장날)은 화면(market-days.js)이 사용자 기기 날짜로 한다.
 * 서버는 장 서는 날의 끝자리(marketDays: "1,6")만 준다.
 */
@RestController
@RequestMapping("/api/markets")
@RequiredArgsConstructor
public class MarketController {

    static final int DEFAULT_RADIUS = 1000;
    static final int MAX_RADIUS = 5000;
    static final int MAX_RESULTS = 100;
    static final int NEARBY_STORE_RADIUS = 300;

    private final MarketRepository repository;
    private final MarketFacilityRepository facilityRepository;

    public record Summary(Long id, String name, String marketType, boolean permanent, String marketDays,
                          String address, BigDecimal latitude, BigDecimal longitude, Integer storeCount,
                          boolean hasToilet, boolean hasParking, Boolean arcade, Boolean kidsFriendly, long distanceM) {
    }

    /** 시설 정보 (true 있음 / false 없음 / null 정보 없음). 출처·기준일과 함께 */
    public record Facilities(Boolean customerParking, Boolean arcade, Boolean elevator, Boolean shoppingCart,
                             Boolean lockers, Boolean bikeRack, Boolean nursingRoom, Boolean kidsRoom, Boolean restArea,
                             Boolean helpCenter, Boolean foreignDesk, Boolean aed, String source, LocalDate dataDate) {
        static Facilities of(MarketFacility f) {
            return new Facilities(f.getCustomerParking(), f.getArcade(), f.getElevator(), f.getShoppingCart(),
                    f.getLockers(), f.getBikeRack(), f.getNursingRoom(), f.getKidsRoom(), f.getRestArea(),
                    f.getHelpCenter(), f.getForeignDesk(), f.getAed(), f.getSource(), f.getDataDate());
        }
    }

    public record NearbyStore(Long id, String name, String type, String category, String infoStatus, long distanceM) {
    }

    public record SameBuilding(Long id, String name) {
    }

    public record Detail(Long id, String name, String marketType, boolean permanent, String cycleRaw, String marketDays,
                         String roadAddress, String jibunAddress, BigDecimal latitude, BigDecimal longitude,
                         String regionName, Integer storeCount, List<String> goods, String homepageUrl,
                         boolean hasToilet, boolean hasParking, Integer openedYear, String source, LocalDate dataDate,
                         Facilities facilities,
                         int nearbyRadiusM, List<NearbyStore> nearbyStores, List<SameBuilding> sameBuilding) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Summary> nearby(@RequestParam double lat, @RequestParam double lng,
                                @RequestParam(required = false) Integer radius) {
        if (lat < 33 || lat > 39 || lng < 124 || lng > 132) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lat, lng가 대한민국 범위를 벗어났어요.");
        }
        int r = radius == null ? DEFAULT_RADIUS : Math.clamp(radius, 100, MAX_RADIUS);
        return repository.findNearby(lat, lng, r, MAX_RESULTS).stream()
                .map(m -> new Summary(m.getId(), m.getName(), m.getMarketType(), Boolean.TRUE.equals(m.getPermanent()),
                        m.getMarketDays(), m.getRoadAddress() != null ? m.getRoadAddress() : m.getJibunAddress(),
                        m.getLatitude(), m.getLongitude(), m.getStoreCount(),
                        Boolean.TRUE.equals(m.getHasToilet()), Boolean.TRUE.equals(m.getHasParking()),
                        m.getArcade(), m.getKidsFriendly() == null ? null : m.getKidsFriendly() == 1, Math.round(m.getDistanceM())))
                .toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public Detail detail(@PathVariable Long id) {
        Market m = repository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.MARKET_NOT_FOUND));
        List<NearbyStore> stores = List.of();
        List<SameBuilding> same = List.of();
        if (m.getLatitude() != null && m.getLongitude() != null) {
            stores = repository.findStoresNear(m.getLatitude().doubleValue(), m.getLongitude().doubleValue(), NEARBY_STORE_RADIUS)
                    .stream().map(s -> new NearbyStore(s.getId(), s.getName(), s.getType(), s.getCategory(),
                            s.getInfoStatus(), Math.round(s.getDistanceM()))).toList();
            same = repository.findSameBuilding(m.getLatitude(), m.getLongitude(), m.getId())
                    .stream().map(o -> new SameBuilding(o.getId(), o.getName())).toList();
        }
        String regionName = null;
        if (m.getSigunguCode() != null) {
            regionName = repository.findRegion(m.getSigunguCode())
                    .map(r -> r.getSidoName() + " " + r.getSigunguName()).orElse(null);
        }
        Facilities facilities = m.getMarketCode() == null ? null
                : facilityRepository.findById(m.getMarketCode()).map(Facilities::of).orElse(null);
        List<String> goods = m.getGoods() == null ? List.of()
                : List.of(m.getGoods().split("\\+")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new Detail(m.getId(), m.getName(), m.getMarketType(), m.isPermanent(), m.getCycleRaw(), m.getMarketDays(),
                m.getRoadAddress(), m.getJibunAddress(), m.getLatitude(), m.getLongitude(), regionName,
                m.getStoreCount(), goods, m.getHomepageUrl(), m.isHasToilet(), m.isHasParking(), m.getOpenedYear(),
                m.getSource(), m.getDataDate(), facilities, NEARBY_STORE_RADIUS, stores, same);
    }
}
