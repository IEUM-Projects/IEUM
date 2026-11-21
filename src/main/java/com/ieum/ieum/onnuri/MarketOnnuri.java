package com.ieum.ieum.onnuri;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** 전통시장의 온누리상품권 가맹점 수 (sql/15_schema_onnuri.sql, 데이터는 16_data_onnuri.sql) */
@Entity
@Table(name = "market_onnuri")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketOnnuri {

    @Id
    @Column(name = "market_id")
    private Long marketId;

    @Column(name = "merchant_count", nullable = false)
    private int merchantCount;

    @Column(name = "digital_count", nullable = false)
    private int digitalCount;

    @Column(name = "data_date", nullable = false)
    private LocalDate dataDate;
}
