package com.ieum.ieum.report;

/** 제보 처리 상태. 지금은 Workbench에서 직접 바꾸고, 나중에 관리자 화면에서 바꾼다 */
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
