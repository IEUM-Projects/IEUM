package com.ieum.ieum.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * stores 테이블과 연결되는 클래스 (Entity).
 *
 * - 테이블은 schema.sql로 이미 만들어 두었고, ddl-auto: validate 설정이라
 *   Spring은 이 클래스와 테이블 모양이 맞는지 "검사만" 하고 테이블을 바꾸지 않는다.
 * - 그래서 각 필드의 타입과 columnDefinition을 실제 테이블과 똑같이 맞춰 두었다.
 */
@Entity
@Table(name = "stores")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA가 쓰는 기본 생성자. 밖에서 new Store() 못 하게 막음
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // AUTO_INCREMENT
    private Long id;

    // 문자열 enum은 MySQL에서 기본으로 ENUM 타입을 기대하므로, 실제 테이블처럼 varchar로 지정
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(10)")
    private StoreType type;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 20)
    private String category;

    @Column(length = 30)
    private String phone;

    @Column(length = 200)
    private String hours;

    @Column(columnDefinition = "text")
    private String history;

    @Column(name = "certified_year")
    private Short certifiedYear;

    @Column(name = "sns_url", length = 300)
    private String snsUrl;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(nullable = false, length = 50)
    private String source;

    @Column(name = "source_no")
    private Integer sourceNo;

    @Column(name = "address_raw", length = 300)
    private String addressRaw;

    @Column(name = "road_address", length = 300)
    private String roadAddress;

    @Column(name = "jibun_address", length = 300)
    private String jibunAddress;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "sido_code", columnDefinition = "char(2)")
    private String sidoCode;

    @Column(name = "sigungu_code", columnDefinition = "char(5)")
    private String sigunguCode;

    @Column(name = "b_code", columnDefinition = "char(10)")
    private String bCode;

    @Column(name = "kakao_place_id", length = 20)
    private String kakaoPlaceId;

    @Column(name = "geocode_status", nullable = false, length = 15)
    private String geocodeStatus;

    @Column(name = "enrich_status", nullable = false, length = 15)
    private String enrichStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "info_status", nullable = false, columnDefinition = "varchar(12)")
    private InfoStatus infoStatus;

    @Column(name = "is_visible", nullable = false)
    private boolean visible;

    // DB가 자동으로 채우는 값이라 JPA가 직접 쓰지 않도록 막음
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
