package com.ieum.ieum.license;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StoreLicenseCheckRepository extends JpaRepository<StoreLicenseCheck, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StoreLicenseCheck c where c.storeId = :id")
    Optional<StoreLicenseCheck> findForUpdate(@Param("id") Long storeId);

    @Query("select c from StoreLicenseCheck c where c.review = :review order by c.checkedAt desc, c.storeId")
    Page<StoreLicenseCheck> findByReview(@Param("review") StoreLicenseCheck.Review review, Pageable pageable);

    @Query("select c.result, count(c) from StoreLicenseCheck c group by c.result")
    List<Object[]> countByResult();

    @Query("select c.review, count(c) from StoreLicenseCheck c group by c.review")
    List<Object[]> countByReview();

    @Query("select max(c.checkedAt) from StoreLicenseCheck c")
    LocalDateTime lastCheckedAt();
}
