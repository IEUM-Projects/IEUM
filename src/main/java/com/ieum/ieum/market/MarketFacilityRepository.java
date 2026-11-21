package com.ieum.ieum.market;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MarketFacilityRepository extends JpaRepository<MarketFacility, String> {

    /** 관리자 확인: 같은 줄을 두 관리자가 동시에 처리하지 않게 잠그고 읽는다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from MarketFacility f where f.marketCode = :code")
    Optional<MarketFacility> findForUpdate(@Param("code") String code);

    @Query("select f from MarketFacility f where f.linkStatus = :status order by f.sido, f.name, f.marketCode")
    Page<MarketFacility> findByStatus(@Param("status") MarketFacility.LinkStatus status, Pageable pageable);

    @Query("select f.linkStatus, count(f) from MarketFacility f group by f.linkStatus")
    List<Object[]> countByStatus();
}
