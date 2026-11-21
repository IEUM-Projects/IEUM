-- 30. 동네 이야기 사진 (COM-02)
--   post_photos : 글에 붙인 사진. 파일은 DB가 아니라 저장소(지금은 서버 폴더 ieum.upload.dir)에 있고, 여기에는 이름(file_key)만 둔다.
--     post_id    : 붙은 글. 올리고 아직 글에 붙이지 않은 사진은 NULL (하루가 지나면 새벽 정리 때 지움)
--     user_id    : 올린 회원
--     file_key   : 무작위 32자 (파일: <key>.jpg 큰 사진, <key>_t.jpg 목록용 작은 사진)
--     alt        : 사진 설명 (화면 낭독기용, 선택)
--     sort_order : 글 안의 순서 (0부터)
--   사진은 서버에서 다시 저장하면서 촬영 위치(GPS)·기기 정보 같은 사진 속 정보를 모두 지운다.
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다.
USE ieum;

CREATE TABLE IF NOT EXISTS post_photos (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    post_id     BIGINT       NULL     COMMENT '붙은 글 (아직 안 붙였으면 NULL)',
    user_id     BIGINT       NOT NULL COMMENT '올린 회원',
    file_key    CHAR(32)     NOT NULL COMMENT '저장소 파일 이름 (무작위)',
    width       INT          NOT NULL,
    height      INT          NOT NULL,
    bytes       INT          NOT NULL COMMENT '큰 사진 크기',
    alt         VARCHAR(100) NULL     COMMENT '사진 설명 (선택)',
    sort_order  TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_post_photos_key (file_key),
    KEY idx_post_photos_post (post_id, sort_order),
    KEY idx_post_photos_user (user_id, post_id, created_at),
    CONSTRAINT fk_post_photos_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_post_photos_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '동네 이야기 사진 (COM-02)';
