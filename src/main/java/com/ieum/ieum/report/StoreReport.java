package com.ieum.ieum.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** store_reports 테이블 (sql/06_schema_report.sql, 처리 기록 칸은 09_schema_admin.sql) */
@Entity
@Table(name = "store_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, columnDefinition = "varchar(20)")
    private ReportType type;

    @Column(length = 500)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(10)")
    private ReportStatus status;

    @Column(name = "admin_note", length = 300)
    private String adminNote;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public static StoreReport create(Long storeId, Long userId, ReportType type, String content, LocalDate today) {
        StoreReport report = new StoreReport();
        report.storeId = storeId;
        report.userId = userId;
        report.type = type;
        report.content = content;
        report.status = ReportStatus.RECEIVED;
        report.reportDate = today;
        return report;
    }

    public boolean isReceived() {
        return status == ReportStatus.RECEIVED;
    }

    /** 관리자 처리 (ADM-01): 반영 또는 반려. 메모는 제보한 사람에게 보인다 */
    public void review(ReportStatus decision, String note, Long adminId, LocalDateTime now) {
        if (decision == ReportStatus.RECEIVED) throw new IllegalArgumentException("처리 결과는 반영 또는 반려여야 해요.");
        this.status = decision;
        this.adminNote = note;
        this.reviewedBy = adminId;
        this.reviewedAt = now;
    }
}
