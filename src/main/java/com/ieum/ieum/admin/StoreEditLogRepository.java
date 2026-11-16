package com.ieum.ieum.admin;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoreEditLogRepository extends JpaRepository<StoreEditLog, Long> {

    List<StoreEditLog> findByStoreIdOrderByCreatedAtDescIdDesc(Long storeId, Pageable limit);
}
