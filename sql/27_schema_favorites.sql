-- 27. 찜한 곳 (FAV-01): 회원이 저장해 둔 가게·시장
--   favorites : 회원 + 대상(STORE 가게 / MARKET 시장) 한 줄. 같은 곳은 한 번만 (UNIQUE)
-- 회원 줄을 지우면 그 회원의 찜도 함께 지워진다 (ON DELETE CASCADE). 탈퇴는 서버가 찜을 지운다.
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다 (이미 있으면 건너뜀).
USE ieum;

CREATE TABLE IF NOT EXISTS favorites (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL COMMENT '찜한 회원',
    target_type  VARCHAR(10) NOT NULL COMMENT 'STORE (가게) | MARKET (시장)',
    target_id    BIGINT      NOT NULL COMMENT 'stores.id 또는 markets.id',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '찜한 때',
    PRIMARY KEY (id),
    UNIQUE KEY uk_favorites_user_target (user_id, target_type, target_id),
    KEY idx_favorites_user_created (user_id, created_at),
    KEY idx_favorites_target (target_type, target_id),
    CONSTRAINT fk_favorites_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '찜한 가게·시장 (FAV-01)';
