package com.ieum.ieum.market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 전통시장 시설 정보 (sql/12_schema_market_facility.sql, 데이터는 13_data_market_facilities.sql)
 * 소상공인시장진흥공단 '전통시장현황' 한 줄. 시설 값은 true 있음 / false 없음 / null 정보 없음.
 * 시장과는 markets.market_code 로 이어진다.
 */
@Entity
@Table(name = "market_facilities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketFacility {

    public enum LinkStatus { AUTO, PENDING, LINKED, IGNORED }

    @Id
    @Column(name = "market_code", length = 20)
    private String marketCode;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "road_address", length = 300)
    private String roadAddress;

    @Column(name = "jibun_address", length = 300)
    private String jibunAddress;

    @Column(length = 20)
    private String sido;

    @Column(length = 30)
    private String sigungu;

    private Boolean arcade;
    private Boolean elevator;
    @Column(name = "help_center")
    private Boolean helpCenter;
    @Column(name = "kids_room")
    private Boolean kidsRoom;
    @Column(name = "rest_area")
    private Boolean restArea;
    @Column(name = "nursing_room")
    private Boolean nursingRoom;
    private Boolean lockers;
    @Column(name = "bike_rack")
    private Boolean bikeRack;
    @Column(name = "shopping_cart")
    private Boolean shoppingCart;
    @Column(name = "foreign_desk")
    private Boolean foreignDesk;
    @Column(name = "customer_parking")
    private Boolean customerParking;
    private Boolean aed;

    @Column(nullable = false, length = 60)
    private String source;

    @Column(name = "data_date", nullable = false)
    private LocalDate dataDate;

    @Column(name = "link_status", nullable = false, length = 10)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private LinkStatus linkStatus;

    @Column(name = "suggested_market_id")
    private Long suggestedMarketId;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    public String address() {
        return roadAddress != null ? roadAddress : jibunAddress;
    }

    public void review(LinkStatus status, Long adminId) {
        linkStatus = status;
        reviewedBy = adminId;
        reviewedAt = LocalDateTime.now();
    }
}
