package com.ieum.ieum.admin;

/** 관리자가 고칠 수 있는 가게 정보 (store_edit_logs.field) */
public enum StoreEditField {
    INFO_STATUS("영업 상태"),
    HOURS("영업시간"),
    PHONE("전화번호"),
    ROAD_ADDRESS("도로명 주소");

    private final String label;

    StoreEditField(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
