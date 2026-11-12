package com.ieum.ieum.region;

import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * 지역(regions) 조회.
 *
 * regions 테이블에는 좌표가 없어서, 그 지역에 있는 가게들의 평균 위치를 "지역 중심"으로 쓴다.
 * (위치 권한을 거부한 사용자가 지역을 고르면 지도를 그 중심으로 옮기기 위해 필요)
 *
 * Repository<Region, String>: 저장·삭제 같은 기본 메서드 없이 아래 쿼리만 쓰는 조회 전용 Repository
 */
public interface RegionRepository extends Repository<Region, String> {

    @Query(value = """
            SELECT r.code          AS code,
                   r.sido_code     AS sidoCode,
                   r.sido_name     AS sidoName,
                   r.sigungu_name  AS sigunguName,
                   AVG(s.latitude)  AS centerLat,
                   AVG(s.longitude) AS centerLng,
                   COUNT(s.id)      AS storeCount
            FROM regions r
            JOIN stores s ON s.sigungu_code = r.code AND s.is_visible = 1
            GROUP BY r.code, r.sido_code, r.sido_name, r.sigungu_name
            ORDER BY r.sido_code, r.sigungu_name
            """, nativeQuery = true)
    List<RegionRow> findAllWithCenter();

    interface RegionRow {
        String getCode();
        String getSidoCode();
        String getSidoName();
        String getSigunguName();
        Double getCenterLat();
        Double getCenterLng();
        Long getStoreCount();
    }
}
