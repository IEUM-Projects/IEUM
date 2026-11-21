package com.ieum.ieum.festival;

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

/**
 * festivals 테이블. 서버가 공공데이터 API에서 받아 채운다 (FestivalSyncService).
 * 표는 sql/schema_festival.sql로 만들고, ddl-auto: validate가 모양을 검사한다.
 */
@Entity
@Table(name = "festivals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Festival {

    /** 60일보다 길면 '기간 중 계속 열리는 행사'로 따로 보여 준다 (달력이 점으로 가득 차지 않게) */
    public static final int LONG_TERM_DAYS = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dedupe_key", nullable = false, length = 250)
    private String dedupeKey;

    @Column(nullable = false, length = 10)
    private String source;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 300)
    private String place;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 200)
    private String organizer;

    @Column(length = 200)
    private String host;

    @Column(length = 300)
    private String sponsor;

    @Column(length = 50)
    private String phone;

    @Column(name = "homepage_url", length = 500)
    private String homepageUrl;

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

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "image_license", length = 10)
    private String imageLicense;

    @Column(name = "tour_content_id", length = 20)
    private String tourContentId;

    @Column(name = "tour_detail_at")
    private LocalDateTime tourDetailAt;

    @Column(length = 100)
    private String provider;

    @Column(name = "data_date")
    private LocalDate dataDate;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public static Festival create(String dedupeKey) {
        Festival festival = new Festival();
        festival.dedupeKey = dedupeKey;
        festival.geocodeStatus = "NONE";
        return festival;
    }

    /**
     * API에서 받은 최신 내용으로 덮어쓴다.
     * 단, 원본에 좌표가 없을 때는 예전에 브이월드로 찾아 둔 좌표·지역을 지우지 않는다 (매일 다시 찾지 않도록).
     */
    public void applyFrom(FestivalRecord r, LocalDateTime seenAt) {
        boolean addressChanged = !same(roadAddress, r.roadAddress()) || !same(jibunAddress, r.jibunAddress());
        source = r.source();
        name = r.name();
        place = r.place();
        startDate = r.startDate();
        endDate = r.endDate();
        // 관광공사 소개글을 따로 받아 둔 경우, 원본에 내용이 없다고 지우지 않는다
        if (r.description() != null || tourDetailAt == null) description = r.description();
        organizer = r.organizer();
        host = r.host();
        sponsor = r.sponsor();
        if (r.phone() != null || tourDetailAt == null) phone = r.phone();
        if (r.homepageUrl() != null || tourDetailAt == null) homepageUrl = r.homepageUrl();
        roadAddress = r.roadAddress();
        jibunAddress = r.jibunAddress();
        imageUrl = r.imageUrl();
        imageLicense = r.imageLicense();
        tourContentId = r.tourContentId();
        provider = r.provider();
        dataDate = r.dataDate();
        lastSeenAt = seenAt;

        if (r.latitude() != null && r.longitude() != null) {
            BigDecimal lat = scale(r.latitude());
            BigDecimal lng = scale(r.longitude());
            if (!"SOURCE".equals(geocodeStatus) || !same(latitude, lat) || !same(longitude, lng)) {
                sidoCode = null;      // 좌표가 바뀌면 지역을 다시 찾는다
                sigunguCode = null;
            }
            latitude = lat;
            longitude = lng;
            geocodeStatus = "SOURCE";
        } else if (addressChanged || "SOURCE".equals(geocodeStatus)) {
            // 원본 좌표가 사라졌거나 주소가 바뀌었으면, 주소로 다시 찾도록 비운다
            // (그 밖에는 예전에 브이월드로 찾은 좌표나 '못 찾음' 표시를 그대로 둔다)
            latitude = null;
            longitude = null;
            sidoCode = null;
            sigunguCode = null;
            geocodeStatus = "NONE";
        }
    }

    public void geocoded(double lat, double lng) {
        latitude = scale(lat);
        longitude = scale(lng);
        geocodeStatus = "VWORLD";   // 브이월드 주소 검색으로 찾음 (DATA-05, 예전 KAKAO 는 23번 SQL로 지움)
    }

    public void geocodeFailed() {
        geocodeStatus = "FAILED";
    }

    public void region(String sigungu) {
        sigunguCode = sigungu;
        sidoCode = sigungu.substring(0, 2);
    }

    /** 관광공사 상세 조회 결과(소개글, 홈페이지, 전화)로 빈 곳을 채운다 */
    public void tourDetail(String overview, String homepage, String tel, LocalDateTime at) {
        if (description == null && overview != null) description = overview;
        if (homepageUrl == null && homepage != null) homepageUrl = homepage;
        if (phone == null && tel != null) phone = tel;
        tourDetailAt = at;
    }

    public boolean isLongTerm() {
        return startDate.plusDays(LONG_TERM_DAYS).isBefore(endDate);
    }

    public String address() {
        return roadAddress != null ? roadAddress : jibunAddress;
    }

    private static BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(7, RoundingMode.HALF_UP);
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        return a == null ? b == null : a.equals(b);
    }
}
