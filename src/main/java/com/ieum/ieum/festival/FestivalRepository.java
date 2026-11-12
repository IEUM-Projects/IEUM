package com.ieum.ieum.festival;

import com.ieum.ieum.region.Region;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface FestivalRepository extends JpaRepository<Festival, Long> {

    /** 기간(from~to)과 하루라도 겹치는 축제. sido가 null이면 전국 */
    @Query("""
            select f from Festival f
            where f.startDate <= :to and f.endDate >= :from
              and (:sido is null or f.sidoCode = :sido)
            order by f.startDate, f.name
            """)
    List<Festival> findOverlapping(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("sido") String sido);

    /** 지역 이름 표시용 */
    @Query("select r from Region r where r.code in :codes")
    List<Region> findRegions(@Param("codes") Collection<String> codes);

    // ---------------------------------------------------------------- 동기화용

    /** 좌표가 없어서 주소로 찾아야 하는 축제 */
    @Query("""
            select f from Festival f
            where f.latitude is null and f.geocodeStatus = 'NONE'
              and (f.roadAddress is not null or f.jibunAddress is not null)
            """)
    List<Festival> findNeedingGeocode();

    /** 좌표는 있는데 지역 코드가 없는 축제 */
    @Query("select f from Festival f where f.latitude is not null and f.sigunguCode is null")
    List<Festival> findNeedingRegion();

    /** 관광공사 소개글을 아직 받지 않은, 끝나지 않은 축제 (가까운 날짜부터) */
    @Query("""
            select f from Festival f
            where f.tourContentId is not null and f.tourDetailAt is null and f.endDate >= :today
            order by f.startDate
            """)
    List<Festival> findNeedingTourDetail(@Param("today") LocalDate today, Pageable limit);

    /** 이번 동기화에서 두 API 어디에도 없었던 축제 (원본에서 삭제된 것) */
    @Modifying
    @Query("delete from Festival f where f.lastSeenAt < :runStart")
    int deleteNotSeenSince(@Param("runStart") LocalDateTime runStart);
}
