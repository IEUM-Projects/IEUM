package com.ieum.ieum.report;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface StoreReportRepository extends JpaRepository<StoreReport, Long> {

    boolean existsByUserIdAndStoreIdAndTypeAndReportDate(Long userId, Long storeId, ReportType type, LocalDate reportDate);

    long countByUserIdAndReportDate(Long userId, LocalDate reportDate);

    List<StoreReport> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable limit);

    /** 내 제보 목록에 가게 이름을 붙이기 위한 조회: [가게 id, 가게 이름, 지도에 보이는지] */
    @Query("select s.id, s.name, s.visible from Store s where s.id in :ids")
    List<Object[]> findStoreNames(@Param("ids") Collection<Long> ids);

    // ---------------------------------------------------------------- 관리자 (ADM-01)

    /** 처리 대기 제보를 가게별로 묶은 줄: 가장 오래 기다린 가게부터 */
    interface PendingGroup {
        Long getStoreId();
        Long getReportCount();
    }

    @Query(value = """
            SELECT r.store_id AS storeId, COUNT(*) AS reportCount
            FROM store_reports r
            WHERE r.status = 'RECEIVED'
            GROUP BY r.store_id
            ORDER BY MIN(r.created_at), r.store_id
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<PendingGroup> findPendingGroups(@Param("limit") int limit, @Param("offset") int offset);

    @Query(value = "SELECT COUNT(DISTINCT store_id) FROM store_reports WHERE status = 'RECEIVED'", nativeQuery = true)
    long countPendingStores();

    /** 상태별 제보 수: [상태, 건수] */
    @Query("select r.status, count(r) from StoreReport r group by r.status")
    List<Object[]> countByStatus();

    List<StoreReport> findByStoreIdInAndStatusOrderByCreatedAtAscIdAsc(Collection<Long> storeIds, ReportStatus status);

    /** 처리 내역 (반영됨 / 반려됨): 최근에 처리한 순 */
    Page<StoreReport> findByStatusOrderByReviewedAtDescIdDesc(ReportStatus status, Pageable page);

    List<StoreReport> findByStoreIdOrderByCreatedAtDescIdDesc(Long storeId, Pageable limit);

    /** 처리할 제보를 잠그고 읽는다 (두 관리자가 같은 제보를 동시에 처리하지 못하게) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StoreReport r where r.id in :ids")
    List<StoreReport> findAllForUpdate(@Param("ids") Collection<Long> ids);
}
