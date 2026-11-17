package com.ieum.ieum.store;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * stores 테이블을 조회하는 Repository.
 * JpaRepository를 상속하면 findById, findAll 같은 기본 메서드가 자동으로 생긴다.
 */
public interface StoreRepository extends JpaRepository<Store, Long> {

    /** 지도에 보이는 가게 1곳 (숨김 처리된 가게는 찾지 않음) */
    Optional<Store> findByIdAndVisibleTrue(Long id);

    /** 관리자 처리 중 같은 가게를 동시에 고치지 않도록 행을 잠그고 읽는다 (트랜잭션이 끝나면 풀림) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Store s where s.id = :id")
    Optional<Store> findForUpdate(@Param("id") Long id);

    /**
     * 현재 위치 기준 반경 검색 (MAP-01).
     *
     * 1) latitude/longitude BETWEEN ... : 먼저 네모 범위로 좁혀서 위치 인덱스를 사용 (빠름)
     * 2) ST_Distance_Sphere              : 그 안에서 실제 거리(m)를 계산해 원 안에 있는 것만 남김
     *
     * 카테고리 필터: allCategories가 true면 전체, false면 categories 목록에 있는 것만.
     */
    @Query(value = """
            SELECT s.id              AS id,
                   s.name            AS name,
                   s.type            AS type,
                   s.category        AS category,
                   s.phone           AS phone,
                   s.road_address    AS roadAddress,
                   s.latitude        AS latitude,
                   s.longitude       AS longitude,
                   s.info_status     AS infoStatus,
                   ROUND(ST_Distance_Sphere(POINT(s.longitude, s.latitude), POINT(:lng, :lat))) AS distanceM,
                   CASE WHEN o.store_id IS NULL THEN 0 ELSE 1 END AS onnuri
            FROM stores s
            LEFT JOIN store_onnuri o ON o.store_id = s.id
            WHERE s.is_visible = 1
              AND s.latitude  BETWEEN :lat - :radius / 111320.0
                                  AND :lat + :radius / 111320.0
              AND s.longitude BETWEEN :lng - :radius / (111320.0 * COS(RADIANS(:lat)))
                                  AND :lng + :radius / (111320.0 * COS(RADIANS(:lat)))
              AND ST_Distance_Sphere(POINT(s.longitude, s.latitude), POINT(:lng, :lat)) <= :radius
              AND (:allCategories = TRUE OR s.category IN (:categories))
            ORDER BY distanceM
            LIMIT :limit
            """, nativeQuery = true)
    List<NearbyStoreRow> findNearby(@Param("lat") double lat,
                                    @Param("lng") double lng,
                                    @Param("radius") int radius,
                                    @Param("allCategories") boolean allCategories,
                                    @Param("categories") Collection<String> categories,
                                    @Param("limit") int limit);
}
