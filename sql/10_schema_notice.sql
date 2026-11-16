-- 10. 공지사항 (NOT-01)
-- 관리자가 쓰고 누구나 읽는 공지. 지우지 않고 '숨김'으로 내린다 (언제 무엇을 알렸는지 남기기 위해).
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다.
USE ieum;

CREATE TABLE IF NOT EXISTS notices (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    title       VARCHAR(100)  NOT NULL,
    content     TEXT          NOT NULL COMMENT '글자만 (HTML 아님). 줄바꿈은 그대로 보여 준다',
    pinned      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '1이면 목록 맨 위에 고정',
    visible     TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '0이면 숨김 (관리자만 볼 수 있음)',
    author_id   BIGINT        NOT NULL COMMENT '쓴 관리자 (users.id)',
    updated_by  BIGINT        NULL     COMMENT '마지막으로 고친 관리자',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_notices_list (visible, pinned, created_at),
    CONSTRAINT fk_notices_author FOREIGN KEY (author_id) REFERENCES users (id),
    CONSTRAINT fk_notices_updater FOREIGN KEY (updated_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='공지사항';
