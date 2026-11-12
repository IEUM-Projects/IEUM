package com.ieum.ieum.report;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface StoreReportRepository extends JpaRepository<StoreReport, Long> {

    boolean existsByUserIdAndStoreIdAndTypeAndReportDate(Long userId, Long storeId, ReportType type, LocalDate reportDate);

    long countByUserIdAndReportDate(Long userId, LocalDate reportDate);

    List<StoreReport> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable limit);

    /** 내 제보 목록에 가게 이름을 붙이기 위한 조회: [가게 id, 가게 이름] */
    @Query("select s.id, s.name from Store s where s.id in :ids")
    List<Object[]> findStoreNames(@Param("ids") Collection<Long> ids);
}
