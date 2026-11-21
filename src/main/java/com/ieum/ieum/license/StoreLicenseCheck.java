package com.ieum.ieum.license;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/** 가게 한 곳의 가장 최근 인허가 확인 결과 (sql/14_schema_store_license.sql) */
@Entity
@Table(name = "store_license_checks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreLicenseCheck {

    /** ERROR는 예전(API 방식) 값으로, 지금은 쓰지 않는다 */
    public enum Result { OPEN, CLOSED, SUSPENDED, NOT_FOUND, NOT_TARGET, ERROR }

    public enum Review { NONE, PENDING, CLOSED, DISMISSED }

    @Id
    @Column(name = "store_id")
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Result result;

    @Column(name = "license_kind", length = 40)
    private String licenseKind;

    @Column(name = "license_id", length = 60)
    private String licenseId;

    @Column(name = "license_name", length = 200)
    private String licenseName;

    @Column(name = "license_address", length = 300)
    private String licenseAddress;

    @Column(name = "license_status", length = 40)
    private String licenseStatus;

    @Column(name = "closed_on")
    private LocalDate closedOn;

    @Column(name = "match_note", length = 100)
    private String matchNote;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Review review;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    public StoreLicenseCheck(Long storeId) {
        this.storeId = storeId;
        this.review = Review.NONE;
    }

    /**
     * 새 확인 결과를 적고, 관리자 확인 상태를 정한다.
     * - 폐업·휴업: 가게가 이미 폐업이면 CLOSED, 관리자가 '영업 중 맞음'으로 넘긴 같은 인허가·같은 상태면 그대로, 아니면 확인 대기
     * - 영업 중 / 찾지 못함 / 대상 아님: 확인 대기였으면 풀어 줌 (다시 영업 중으로 바뀐 경우 등)
     */
    public void record(Result newResult, LicenseRecord r, String note, boolean storeAlreadyClosed, LocalDateTime now) {
        checkedAt = now;
        matchNote = cut(note, 100);
        boolean sameAsDismissed = review == Review.DISMISSED && r != null
                && Objects.equals(licenseId, r.id()) && Objects.equals(licenseStatus, r.statusText());
        result = newResult;
        licenseKind = r == null ? null : r.kind();
        licenseId = r == null ? null : cut(r.id(), 60);
        licenseName = r == null ? null : cut(r.name(), 200);
        licenseAddress = r == null ? null : cut(r.address(), 300);
        licenseStatus = r == null ? null : cut(r.statusText(), 40);
        closedOn = r == null ? null : r.closedOn();
        if (newResult == Result.CLOSED || newResult == Result.SUSPENDED) {
            if (storeAlreadyClosed) review = Review.CLOSED;
            else if (!sameAsDismissed) review = Review.PENDING;
        } else if (review == Review.PENDING) {
            review = Review.NONE;
        }
    }

    public void review(Review newReview, Long adminId, LocalDateTime now) {
        review = newReview;
        reviewedBy = adminId;
        reviewedAt = now;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
