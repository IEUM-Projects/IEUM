-- 09. 관리자 제보 처리 (ADM-01)
-- - store_reports에 처리한 관리자·처리 시각 칸 추가
-- - store_edit_logs: 관리자가 가게 정보를 고친 기록 (무엇을, 무엇에서 무엇으로, 누가, 언제)
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다 (이미 있으면 건너뜀).
USE ieum;

-- MySQL은 "칸이 없을 때만 추가(ADD COLUMN IF NOT EXISTS)"를 지원하지 않아서,
-- information_schema로 먼저 확인하고 없을 때만 ALTER 문을 실행한다.

-- 1) 처리한 관리자
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE store_reports ADD COLUMN reviewed_by BIGINT NULL COMMENT ''처리한 관리자 (users.id)'' AFTER admin_note',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'store_reports' AND column_name = 'reviewed_by');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) 처리 시각
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE store_reports ADD COLUMN reviewed_at DATETIME NULL COMMENT ''처리 시각'' AFTER reviewed_by',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'store_reports' AND column_name = 'reviewed_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) 처리한 관리자 → 회원 연결
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE store_reports ADD CONSTRAINT fk_store_reports_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id)',
    'DO 0')
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE() AND table_name = 'store_reports' AND constraint_name = 'fk_store_reports_reviewer');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4) 상태별 목록(처리 대기 오래된 순, 처리 내역 최근 순)을 빨리 찾기 위한 색인
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE store_reports ADD KEY idx_store_reports_status (status, created_at)',
    'DO 0')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'store_reports' AND index_name = 'idx_store_reports_status');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5) 가게 정보 수정 기록
CREATE TABLE IF NOT EXISTS store_edit_logs (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    store_id      BIGINT        NOT NULL,
    admin_id      BIGINT        NOT NULL COMMENT '고친 관리자 (users.id)',
    field         VARCHAR(20)   NOT NULL COMMENT 'INFO_STATUS / HOURS / PHONE / ROAD_ADDRESS',
    before_value  VARCHAR(300)  NULL,
    after_value   VARCHAR(300)  NULL,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_store_edit_logs_store (store_id, created_at),
    CONSTRAINT fk_store_edit_logs_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_store_edit_logs_admin FOREIGN KEY (admin_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='가게 정보 수정 기록';
