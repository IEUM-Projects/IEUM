package com.ieum.ieum.notice;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    /** 공개 목록: 고정 공지 먼저, 그다음 최신 순 */
    @Query("select n from Notice n where n.visible = true order by n.pinned desc, n.createdAt desc, n.id desc")
    Page<Notice> findPublic(Pageable page);

    Optional<Notice> findByIdAndVisibleTrue(Long id);

    /** 관리자 목록: 숨긴 공지까지, 고정 먼저 최신 순 */
    @Query("select n from Notice n order by n.pinned desc, n.createdAt desc, n.id desc")
    Page<Notice> findAllForAdmin(Pageable page);

    /** 지금 고정되어 보이는 공지 수 (except: 고치는 중인 공지는 빼고 센다) */
    @Query("select count(n) from Notice n where n.pinned = true and n.visible = true and (:except is null or n.id <> :except)")
    long countPinned(@Param("except") Long except);
}
