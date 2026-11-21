-- 28. 알림 (NOTI-01): 내 글에 댓글, 제보 처리 결과, 내 글·댓글 가림/다시 보임, 추천 처리 결과
--   notifications : 회원 한 사람에게 가는 알림 한 줄.
--     group_key  : 같은 일을 하나로 묶는 열쇠 (예: POST_COMMENT:123 → 안 읽은 동안 '새 댓글 3개'로 합침)
--     count      : 묶인 수
--     read_at    : 읽은 때 (NULL 이면 안 읽음)
-- 90일이 지나면 서버가 매일 지운다. 회원 줄을 지우면 함께 지워진다 (탈퇴는 서버가 바로 지움).
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다.
USE ieum;

CREATE TABLE IF NOT EXISTS notifications (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL COMMENT '받는 회원',
    type        VARCHAR(20)  NOT NULL COMMENT 'COMMENT | REPORT_APPLIED | REPORT_REJECTED | HIDDEN | RESTORED | RECOMMEND',
    title       VARCHAR(100) NOT NULL COMMENT '한 줄 제목',
    body        VARCHAR(300) NULL     COMMENT '설명 (글 제목, 관리자 메모 등)',
    link        VARCHAR(200) NOT NULL COMMENT '누르면 갈 화면 (이음 안의 주소)',
    group_key   VARCHAR(60)  NULL     COMMENT '묶는 열쇠',
    count       INT          NOT NULL DEFAULT 1,
    read_at     DATETIME     NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '마지막으로 묶인 때 (목록 순서)',
    PRIMARY KEY (id),
    KEY idx_notifications_user_updated (user_id, updated_at),
    KEY idx_notifications_user_unread (user_id, read_at),
    KEY idx_notifications_group (user_id, group_key, read_at),
    KEY idx_notifications_created (created_at),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '알림 (NOTI-01)';
