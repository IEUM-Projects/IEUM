package com.ieum.ieum.report;

/** 제보 처리 상태. 관리자 화면(/admin.html, ADM-01)에서 반영·반려한다 */
public enum ReportStatus {
    RECEIVED("접수됨"),
    APPLIED("반영됨"),
    REJECTED("반려됨");

    private final String label;

    ReportStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
