package com.ieum.ieum.onnuri;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** 백년가게의 온누리상품권 가맹 정보 (sql/15_schema_onnuri.sql, 데이터는 16_data_onnuri.sql) */
@Entity
@Table(name = "store_onnuri")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreOnnuri {

    @Id
    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "merchant_name", nullable = false, length = 200)
    private String merchantName;

    @Column(name = "market_name", length = 200)
    private String marketName;

    @Column(nullable = false)
    private boolean paper;

    @Column(nullable = false)
    private boolean digital;

    @Column(name = "registered_year")
    private Short registeredYear;

    @Column(name = "match_note", nullable = false, length = 60)
    private String matchNote;

    @Column(name = "data_date", nullable = false)
    private LocalDate dataDate;
}
