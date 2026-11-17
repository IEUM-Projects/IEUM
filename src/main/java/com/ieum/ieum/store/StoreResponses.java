package com.ieum.ieum.store;

import java.math.BigDecimal;
import java.util.List;

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
            int distanceM,
            boolean onnuri
    ) {
        static Summary from(NearbyStoreRow row) {
            return new Summary(row.getId(), row.getName(), row.getType(), row.getCategory(),
                    row.getPhone(), row.getRoadAddress(), row.getLatitude(), row.getLongitude(),
                    row.getInfoStatus(), row.getDistanceM() == null ? 0 : row.getDistanceM().intValue(),
                    row.getOnnuri() != null && row.getOnnuri() == 1);
        }
    }

    /** 온누리상품권 가맹 (DATA-04). 가맹으로 확인되지 않은 가게는 null (쓸 수 없다는 뜻이 아님) */
    public record Onnuri(boolean paper, boolean digital, String marketName, Short registeredYear, java.time.LocalDate dataDate) {
        static Onnuri of(com.ieum.ieum.onnuri.StoreOnnuri o) {
            return o == null ? null : new Onnuri(o.isPaper(), o.isDigital(), o.getMarketName(), o.getRegisteredYear(), o.getDataDate());
        }
    }

    /** 로컬크리에이터 연도별 선정 내용 (LOC-01, 중소벤처기업부 로컬크리에이터 리스트) */
    public record Selection(int year, String item, List<String> fields, String organizer, java.time.LocalDate dataDate) {
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
            String kakaoMapUrl,
            Onnuri onnuri,
            List<Selection> creatorSelections
    ) {
        static Detail from(Store s, com.ieum.ieum.onnuri.StoreOnnuri onnuri, List<Selection> selections) {
            String mapUrl = s.getKakaoPlaceId() == null ? null
                    : "https://place.map.kakao.com/" + s.getKakaoPlaceId();
            return new Detail(s.getId(), s.getName(), s.getType(), s.getCategory(), s.getPhone(),
                    s.getHours(), s.getHistory(), s.getCertifiedYear(), s.getSnsUrl(),
                    s.getRoadAddress(), s.getJibunAddress(), s.getLatitude(), s.getLongitude(),
                    s.getInfoStatus(), mapUrl, Onnuri.of(onnuri), selections);
        }
    }
}
