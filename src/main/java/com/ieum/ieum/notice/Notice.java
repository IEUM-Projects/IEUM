package com.ieum.ieum.notice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** notices 테이블 (sql/10_schema_notice.sql) */
@Entity
@Table(name = "notices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    /** 글자만 (HTML 아님). 화면은 textContent로 넣고 줄바꿈만 살린다 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private boolean pinned;

    @Column(nullable = false)
    private boolean visible;

    @Column(name = "author_id", nullable = false, updatable = false)
    private Long authorId;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public static Notice write(String title, String content, boolean pinned, boolean visible, Long authorId) {
        Notice notice = new Notice();
        notice.title = title;
        notice.content = content;
        notice.pinned = pinned;
        notice.visible = visible;
        notice.authorId = authorId;
        return notice;
    }

    public void edit(String title, String content, boolean pinned, boolean visible, Long adminId) {
        this.title = title;
        this.content = content;
        this.pinned = pinned;
        this.visible = visible;
        this.updatedBy = adminId;
    }
}
