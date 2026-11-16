package com.ieum.ieum.admin;

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

import java.time.LocalDateTime;

/** store_edit_logs 테이블 (sql/09_schema_admin.sql): 관리자가 가게 정보를 고친 기록. 한 번 쓰면 고치지 않는다 */
@Entity
@Table(name = "store_edit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreEditLog {

    public static final int MAX_VALUE = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private StoreEditField field;

    @Column(name = "before_value", length = MAX_VALUE)
    private String beforeValue;

    @Column(name = "after_value", length = MAX_VALUE)
    private String afterValue;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public static StoreEditLog of(Long storeId, Long adminId, StoreEditField field, String before, String after) {
        StoreEditLog log = new StoreEditLog();
        log.storeId = storeId;
        log.adminId = adminId;
        log.field = field;
        log.beforeValue = cut(before);
        log.afterValue = cut(after);
        return log;
    }

    private static String cut(String value) {
        return value == null || value.length() <= MAX_VALUE ? value : value.substring(0, MAX_VALUE);
    }
}
