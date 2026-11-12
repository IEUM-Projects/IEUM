package com.ieum.ieum.store;

import java.math.BigDecimal;

/**
 * 반경 검색 쿼리 결과 한 줄.
 * 쿼리의 별칭(AS id, AS name ...)과 아래 메서드 이름이 같으면 Spring Data가 자동으로 채워 준다.
 */
public interface NearbyStoreRow {
    Long getId();
    String getName();
    String getType();
    String getCategory();
    String getPhone();
    String getRoadAddress();
    BigDecimal getLatitude();
    BigDecimal getLongitude();
    String getInfoStatus();
    Double getDistanceM();
}
