package com.ieum.ieum.market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** markets 테이블 (sql/07_schema_markets.sql, 데이터는 08_data_markets.sql) */
@Entity
@Table(name = "markets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Market {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_key", nullable = false, length = 250)
    private String sourceKey;

    /** 전통시장현황 시장코드 (market_facilities). 시설 정보와 이어지지 않았으면 null */
    @Column(name = "market_code", length = 20)
    private String marketCode;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "market_type", nullable = false, length = 20)
    private String marketType;

    @Column(name = "is_permanent", nullable = false)
    private boolean permanent;

    @Column(name = "cycle_raw", nullable = false, length = 50)
    private String cycleRaw;

    /** 장 서는 날의 끝자리 "1,6" (0은 10·20·30일). 정기장이 없으면 null */
    @Column(name = "market_days", length = 20)
    private String marketDays;

    @Column(name = "road_address", length = 300)
    private String roadAddress;

    @Column(name = "jibun_address", length = 300)
    private String jibunAddress;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "geocode_status", nullable = false, length = 10)
    private String geocodeStatus;

    @Column(name = "sido_code", columnDefinition = "char(2)")
    private String sidoCode;

    @Column(name = "sigungu_code", columnDefinition = "char(5)")
    private String sigunguCode;

    @Column(name = "store_count")
    private Integer storeCount;

    @Column(length = 300)
    private String goods;

    @Column(name = "homepage_url", length = 300)
    private String homepageUrl;

    @Column(name = "has_toilet", nullable = false)
    private boolean hasToilet;

    @Column(name = "has_parking", nullable = false)
    private boolean hasParking;

    @Column(name = "opened_year", columnDefinition = "smallint")
    private Integer openedYear;

    @Column(nullable = false, length = 60)
    private String source;

    @Column(name = "data_date")
    private LocalDate dataDate;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public String address() {
        return roadAddress != null ? roadAddress : jibunAddress;
    }

    public void geocoded(double lat, double lng) {
        latitude = BigDecimal.valueOf(lat).setScale(7, RoundingMode.HALF_UP);
        longitude = BigDecimal.valueOf(lng).setScale(7, RoundingMode.HALF_UP);
        geocodeStatus = "KAKAO";
    }

    /** 시설 정보와 잇기 / 풀기 (관리자 확인, DATA-01) */
    public void linkFacility(String code) {
        marketCode = code;
    }

    public void unlinkFacility() {
        marketCode = null;
    }

    public void geocodeFailed() {
        geocodeStatus = "FAILED";
    }

    public void region(String sigungu) {
        sigunguCode = sigungu;
        sidoCode = sigungu.substring(0, 2);
    }
}
