package com.ieum.ieum.store;

import java.math.BigDecimal;

/**
 * API가 돌려주는 응답 모양 (DTO).
 * Entity를 그대로 내보내지 않고 화면에 필요한 값만 골라서 보낸다.
 * record: 값만 담는 클래스를 짧게 만드는 자바 문법 (getter, 생성자 자동 생성)
 */
public final class StoreResponses {

    private StoreResponses() {
    }

    /** 지도 핀·목록용 요약 정보 */
    public record Summary(
            Long id,
            String name,
            String type,
            String category,
            String phone,
            String roadAddress,
            BigDecimal latitude,
            BigDecimal longitude,
            String infoStatus,
            int distanceM
    ) {
        static Summary from(NearbyStoreRow row) {
            return new Summary(row.getId(), row.getName(), row.getType(), row.getCategory(),
                    row.getPhone(), row.getRoadAddress(), row.getLatitude(), row.getLongitude(),
                    row.getInfoStatus(), row.getDistanceM() == null ? 0 : row.getDistanceM().intValue());
        }
    }

    /** 가게 상세 화면용 (MAP-02) */
    public record Detail(
            Long id,
            String name,
            StoreType type,
            String category,
            String phone,
            String hours,
            String history,
            Short certifiedYear,
            String snsUrl,
            String roadAddress,
            String jibunAddress,
            BigDecimal latitude,
            BigDecimal longitude,
            InfoStatus infoStatus,
            String kakaoMapUrl
    ) {
        static Detail from(Store s) {
            String mapUrl = s.getKakaoPlaceId() == null ? null
                    : "https://place.map.kakao.com/" + s.getKakaoPlaceId();
            return new Detail(s.getId(), s.getName(), s.getType(), s.getCategory(), s.getPhone(),
                    s.getHours(), s.getHistory(), s.getCertifiedYear(), s.getSnsUrl(),
                    s.getRoadAddress(), s.getJibunAddress(), s.getLatitude(), s.getLongitude(),
                    s.getInfoStatus(), mapUrl);
        }
    }
}
