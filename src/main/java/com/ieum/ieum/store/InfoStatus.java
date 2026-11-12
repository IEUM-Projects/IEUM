package com.ieum.ieum.store;

/** 영업 정보 상태 (기획안 B안) */
public enum InfoStatus {
    VERIFIED,    // 영업 확인됨
    UNVERIFIED,  // 영업 정보 미확인 → 화면에 배지 + 제보 버튼
    CLOSED       // 폐업 확인 → 지도에 표시하지 않음
}
