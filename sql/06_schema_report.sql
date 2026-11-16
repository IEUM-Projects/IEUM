-- 06. 영업 정보 제보 표 (STO-01)
-- 다른 표는 건드리지 않는다. 여러 번 실행해도 안전하다(IF NOT EXISTS).
USE ieum;

CREATE TABLE IF NOT EXISTS store_reports (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    store_id     BIGINT        NOT NULL,
    user_id      BIGINT        NOT NULL,
    report_type  VARCHAR(20)   NOT NULL COMMENT 'CLOSED(폐업) / HOURS(영업시간) / PHONE(전화번호) / LOCATION(주소·위치) / OTHER(기타)',
    content      VARCHAR(500)  NULL     COMMENT '제보 설명 (선택)',
    status       VARCHAR(10)   NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED(접수됨) / APPLIED(반영됨) / REJECTED(반려됨)',
    admin_note   VARCHAR(300)  NULL     COMMENT '처리 메모 (제보한 사람에게 보여 줌)',
    report_date  DATE          NOT NULL COMMENT '제보한 날 (같은 날 같은 제보를 막는 데 씀)',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 같은 회원이 같은 가게에 같은 종류를 하루 한 번만
    UNIQUE KEY uk_store_reports_daily (user_id, store_id, report_type, report_date),
    KEY idx_store_reports_store (store_id, status),
    KEY idx_store_reports_user (user_id, created_at),
    CONSTRAINT fk_store_reports_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_store_reports_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='영업 정보 제보';
