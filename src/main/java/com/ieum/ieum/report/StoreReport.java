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

/** store_reports 테이블 (sql/schema_report.sql) */
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
}
