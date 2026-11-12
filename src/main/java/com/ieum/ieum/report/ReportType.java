package com.ieum.ieum.report;

/** 제보 종류 (화면에 보이는 이름 포함) */
public enum ReportType {
    CLOSED("폐업했어요"),
    HOURS("영업시간이 달라요"),
    PHONE("전화번호가 달라요"),
    LOCATION("주소·위치가 달라요"),
    OTHER("기타");

    private final String label;

    ReportType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
