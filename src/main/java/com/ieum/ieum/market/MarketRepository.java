package com.ieum.ieum.market;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ieum.ieum.region.Region;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface MarketRepository extends JpaRepository<Market, Long> {

    /** 반경 검색 결과 한 줄 (쿼리 별칭과 메서드 이름이 같으면 자동으로 채워짐) */
    interface NearbyMarketRow {
        Long getId();
        String getName();
        String getMarketType();
        Boolean getPermanent();
        String getMarketDays();
        String getRoadAddress();
        String getJibunAddress();
        BigDecimal getLatitude();
        BigDecimal getLongitude();
        Integer getStoreCount();
        Boolean getHasToilet();
        Boolean getHasParking();
        Double getDistanceM();
    }

    /** 근처 백년가게 한 줄 */
    interface NearbyStoreRow {
        Long getId();
        String getName();
        String getType();
        String getCategory();
        String getInfoStatus();
        Double getDistanceM();
    }

    /** 반경 검색 (가게 검색과 같은 방식: 네모 범위로 좁힌 뒤 실제 거리로 거름) */
    @Query(value = """
            SELECT m.id AS id, m.name AS name, m.market_type AS marketType, m.is_permanent AS permanent,
                   m.market_days AS marketDays, m.road_address AS roadAddress, m.jibun_address AS jibunAddress,
                   m.latitude AS latitude, m.longitude AS longitude, m.store_count AS storeCount,
                   m.has_toilet AS hasToilet, m.has_parking AS hasParking,
                   ROUND(ST_Distance_Sphere(POINT(m.longitude, m.latitude), POINT(:lng, :lat))) AS distanceM
            FROM markets m
            WHERE m.latitude  BETWEEN :lat - :radius / 111320.0 AND :lat + :radius / 111320.0
              AND m.longitude BETWEEN :lng - :radius / (111320.0 * COS(RADIANS(:lat)))
                                  AND :lng + :radius / (111320.0 * COS(RADIANS(:lat)))
              AND ST_Distance_Sphere(POINT(m.longitude, m.latitude), POINT(:lng, :lat)) <= :radius
            ORDER BY distanceM
            LIMIT :limit
            """, nativeQuery = true)
    List<NearbyMarketRow> findNearby(@Param("lat") double lat, @Param("lng") double lng,
                                     @Param("radius") int radius, @Param("limit") int limit);

    /** 시장 근처 백년가게 (지도에 보이는 가게만) */
    @Query(value = """
            SELECT s.id AS id, s.name AS name, s.type AS type, s.category AS category, s.info_status AS infoStatus,
                   ROUND(ST_Distance_Sphere(POINT(s.longitude, s.latitude), POINT(:lng, :lat))) AS distanceM
            FROM stores s
            WHERE s.is_visible = 1
              AND s.latitude  BETWEEN :lat - :radius / 111320.0 AND :lat + :radius / 111320.0
              AND s.longitude BETWEEN :lng - :radius / (111320.0 * COS(RADIANS(:lat)))
                                  AND :lng + :radius / (111320.0 * COS(RADIANS(:lat)))
              AND ST_Distance_Sphere(POINT(s.longitude, s.latitude), POINT(:lng, :lat)) <= :radius
            ORDER BY distanceM
            LIMIT 30
            """, nativeQuery = true)
    List<NearbyStoreRow> findStoresNear(@Param("lat") double lat, @Param("lng") double lng, @Param("radius") int radius);

    /** 같은 건물(같은 좌표)에 있는 다른 시장 */
    @Query("select m from Market m where m.latitude = :lat and m.longitude = :lng and m.id <> :id order by m.name")
    List<Market> findSameBuilding(@Param("lat") BigDecimal lat, @Param("lng") BigDecimal lng, @Param("id") Long id);

    /** 지역 이름 표시용 */
    @Query("select r from Region r where r.code = :code")
    Optional<Region> findRegion(@Param("code") String code);

    // ---------------------------------------------------------------- 좌표·지역 채우기

    @Query("""
            select m from Market m
            where m.latitude is null and m.geocodeStatus = 'NONE'
              and (m.roadAddress is not null or m.jibunAddress is not null)
            """)
    List<Market> findNeedingGeocode();

    @Query("select m from Market m where m.latitude is not null and m.sigunguCode is null")
    List<Market> findNeedingRegion();
}
