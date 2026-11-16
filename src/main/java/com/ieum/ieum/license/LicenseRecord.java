package com.ieum.ieum.license;

import java.time.LocalDate;

/**
 * 인허가 데이터 한 건 (API 응답을 이 모양으로 바꿔서 쓴다)
 *
 * @param state 영업상태를 묶은 것: OPEN 영업·정상 / SUSPENDED 휴업 / CLOSED 폐업·취소·말소 / OTHER 그 밖(제외·삭제 등, 판단에 안 씀)
 */
public record LicenseRecord(String kind, String id, String name, String roadAddress, String jibunAddress,
                            String statusText, State state, LocalDate closedOn) {

    public enum State { OPEN, SUSPENDED, CLOSED, OTHER }

    public String address() {
        return roadAddress != null && !roadAddress.isBlank() ? roadAddress : jibunAddress;
    }
}
