package com.ieum.ieum.festival;

import java.time.LocalDate;

/**
 * API에서 받은 축제 한 건을 정리한 값 (DB에 넣기 전).
 * 표준데이터와 관광공사 데이터를 같은 모양으로 맞춘다.
 */
public record FestivalRecord(
        String source,          // STANDARD / TOUR / BOTH
        String name,
        String place,
        LocalDate startDate,
        LocalDate endDate,
        String description,
        String organizer,
        String host,
        String sponsor,
        String phone,
        String homepageUrl,
        String roadAddress,
        String jibunAddress,
        Double latitude,
        Double longitude,
        String imageUrl,
        String imageLicense,
        String tourContentId,
        String provider,
        LocalDate dataDate
) {

    /** 같은 축제인지 판단하는 열쇠: 정리한 이름 + 시작일 */
    public String dedupeKey() {
        return FestivalText.nameKey(name) + "|" + startDate;
    }

    /** 정보가 얼마나 채워져 있는지 (중복 중 어느 쪽을 남길지 고를 때) */
    public int richness() {
        int score = 0;
        if (latitude != null) score += 4;
        if (homepageUrl != null) score += 2;
        if (phone != null) score += 1;
        if (description != null) score += Math.min(3, description.length() / 40 + 1);
        if (roadAddress != null || jibunAddress != null) score += 1;
        // 같은 내용이면 한국관광공사가 대신 올린 것보다 지자체가 직접 올린 것을 남긴다
        if (provider != null && !provider.contains("한국관광공사")) score += 1;
        return score;
    }

    /**
     * 같은 축제가 두 번 올라온 경우: 이 행을 기본으로 하고 빈 칸만 다른 행에서 채운다.
     * 주소·좌표는 서로 다른 장소가 섞이지 않게, 이 행에 하나도 없을 때만 통째로 가져온다.
     */
    public FestivalRecord fillFrom(FestivalRecord o) {
        boolean noLocation = roadAddress == null && jibunAddress == null && latitude == null;
        return new FestivalRecord(source, name,
                or(place, o.place()), startDate, endDate.isBefore(o.endDate()) ? o.endDate() : endDate,
                or(description, o.description()), or(organizer, o.organizer()), or(host, o.host()),
                or(sponsor, o.sponsor()), or(phone, o.phone()), or(homepageUrl, o.homepageUrl()),
                noLocation ? o.roadAddress() : roadAddress, noLocation ? o.jibunAddress() : jibunAddress,
                noLocation ? o.latitude() : latitude, noLocation ? o.longitude() : longitude,
                or(imageUrl, o.imageUrl()), imageUrl != null ? imageLicense : o.imageLicense(),
                or(tourContentId, o.tourContentId()), provider,
                dataDate == null || (o.dataDate() != null && o.dataDate().isAfter(dataDate)) ? o.dataDate() : dataDate);
    }

    private static <T> T or(T a, T b) {
        return a != null ? a : b;
    }

    /** 관광공사 데이터로 빈 곳을 채운 결과 (같은 축제가 두 곳에 다 있을 때) */
    public FestivalRecord mergeTour(FestivalRecord tour) {
        boolean useTourCoord = latitude == null && tour.latitude() != null;
        return new FestivalRecord("BOTH", name,
                place != null ? place : tour.place(),
                startDate,
                endDate,
                description,
                organizer, host, sponsor,
                phone != null ? phone : tour.phone(),
                homepageUrl,
                roadAddress != null ? roadAddress : tour.roadAddress(),
                jibunAddress,
                useTourCoord ? tour.latitude() : latitude,
                useTourCoord ? tour.longitude() : longitude,
                tour.imageUrl(), tour.imageLicense(), tour.tourContentId(),
                provider, dataDate);
    }
}
