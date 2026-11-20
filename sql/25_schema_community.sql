-- 25. 동네 이야기 게시판 (COM-01)
--   posts            글 (말머리: REVIEW 후기 / RECOMMEND 추천 / QUESTION 질문 / FREE 자유)
--   post_comments    댓글
--   post_likes       공감 (한 사람이 한 글에 한 번)
--   community_reports 글·댓글 신고
-- 글 상태: VISIBLE 보임 / HIDDEN 관리자가 숨김(신고 처리) / TEMP_HIDDEN 권리 침해 주장으로 임시 숨김(정보통신망법 임시조치) / DELETED 지움
-- 회원이 탈퇴해도 글·댓글은 남고, 작성자는 '탈퇴한 회원'으로 보인다 (users 행은 익명으로 남음, POL-01)
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다 (IF NOT EXISTS).
USE ieum;

CREATE TABLE IF NOT EXISTS posts (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    user_id          BIGINT        NOT NULL,
    category         VARCHAR(10)   NOT NULL COMMENT 'REVIEW 후기 / RECOMMEND 추천 / QUESTION 질문 / FREE 자유',
    title            VARCHAR(60)   NOT NULL,
    content          TEXT          NOT NULL COMMENT '글자만 (최대 3000자)',
    place_type       VARCHAR(10)   NULL     COMMENT '이야기를 붙인 곳: STORE / MARKET / FESTIVAL',
    place_id         BIGINT        NULL,
    status           VARCHAR(12)   NOT NULL DEFAULT 'VISIBLE' COMMENT 'VISIBLE / HIDDEN / TEMP_HIDDEN / DELETED',
    hidden_reason    VARCHAR(300)  NULL     COMMENT '숨긴 까닭 (관리자 메모)',
    hidden_until     DATE          NULL     COMMENT '임시 숨김이 끝나는 날 (TEMP_HIDDEN, 최대 30일)',
    recommend_status VARCHAR(10)   NULL     COMMENT '추천 글 처리: PENDING 확인 전 / ADDED 지도에 추가 / EXISTS 이미 있음 / HOLD 보류',
    recommend_note   VARCHAR(300)  NULL     COMMENT '추천 처리 메모 (글쓴이에게 보임)',
    like_count       INT           NOT NULL DEFAULT 0,
    comment_count    INT           NOT NULL DEFAULT 0,
    report_count     INT           NOT NULL DEFAULT 0,
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    edited_at        DATETIME      NULL     COMMENT '글쓴이가 고친 때',
    PRIMARY KEY (id),
    KEY idx_posts_list (status, category, id),
    KEY idx_posts_place (place_type, place_id, status, id),
    KEY idx_posts_user (user_id, id),
    CONSTRAINT fk_posts_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='동네 이야기 게시판 글';

CREATE TABLE IF NOT EXISTS post_comments (
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    post_id       BIGINT         NOT NULL,
    user_id       BIGINT         NOT NULL,
    content       VARCHAR(1000)  NOT NULL,
    status        VARCHAR(12)    NOT NULL DEFAULT 'VISIBLE' COMMENT 'VISIBLE / HIDDEN / TEMP_HIDDEN / DELETED',
    hidden_reason VARCHAR(300)   NULL,
    report_count  INT            NOT NULL DEFAULT 0,
    created_at    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_comments_post (post_id, id),
    KEY idx_comments_user (user_id, id),
    CONSTRAINT fk_comments_post FOREIGN KEY (post_id) REFERENCES posts (id),
    CONSTRAINT fk_comments_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='동네 이야기 댓글';

CREATE TABLE IF NOT EXISTS post_likes (
    post_id     BIGINT    NOT NULL,
    user_id     BIGINT    NOT NULL,
    created_at  DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (post_id, user_id),
    KEY idx_likes_user (user_id),
    CONSTRAINT fk_likes_post FOREIGN KEY (post_id) REFERENCES posts (id),
    CONSTRAINT fk_likes_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='동네 이야기 공감';

CREATE TABLE IF NOT EXISTS community_reports (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    target_type  VARCHAR(10)   NOT NULL COMMENT 'POST / COMMENT',
    target_id    BIGINT        NOT NULL,
    user_id      BIGINT        NOT NULL COMMENT '신고한 회원',
    reason       VARCHAR(12)   NOT NULL COMMENT 'SPAM 광고 / ABUSE 욕설·비방 / PRIVACY 개인정보 / FALSE 사실과 다름 / ETC 기타',
    content      VARCHAR(300)  NULL,
    status       VARCHAR(10)   NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED 접수 / HIDDEN 숨김 처리 / DISMISSED 문제없음',
    reviewed_by  BIGINT        NULL,
    reviewed_at  DATETIME      NULL,
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_reports (target_type, target_id, user_id),
    KEY idx_community_reports_status (status, id),
    CONSTRAINT fk_community_reports_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='동네 이야기 신고';
