-- 21. 시장 이야기 (MKT-02)
-- market_stories   : 시장의 역사·유래를 사람이 확인한 자료로 정리한 글. 출처(자료 이름·링크)가 1개 이상 있어야 한다.
--   status  DRAFT 초안 (관리자만 봄) / PUBLISHED 공개 / HIDDEN 숨김
--   origin  RESEARCH 이음이 공개 자료를 찾아 쓴 초안 (22번 파일) / ADMIN 관리자가 쓴 글
--   updated_by 가 비어 있으면 아직 아무도 고치지 않은 초안 (22번을 다시 실행하면 이런 초안만 새 내용으로 바뀐다)
-- market_tour_info : 한국관광공사 국문 관광정보(쇼핑)에 등록된 같은 시장의 소개글(개요). 서버가 처음 볼 때 불러와 저장한다.
--   status  FOUND 찾음 / NONE 관광공사에 없음 / FAILED 불러오기 실패 (잠시 뒤 다시 시도)
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다 (IF NOT EXISTS).
USE ieum;

CREATE TABLE IF NOT EXISTS market_stories (
    market_id     BIGINT        NOT NULL COMMENT 'markets.id',
    body          TEXT          NOT NULL COMMENT '본문 (글자만, 빈 줄로 문단 구분)',
    sources       JSON          NOT NULL COMMENT '출처 [{"title": "...", "url": "https://..."}] 1~8개',
    status        VARCHAR(10)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED / HIDDEN',
    origin        VARCHAR(10)   NOT NULL DEFAULT 'ADMIN' COMMENT 'RESEARCH 자료 조사 초안 / ADMIN 관리자 작성',
    updated_by    BIGINT        NULL     COMMENT '마지막으로 고친 관리자 (users.id)',
    published_at  DATETIME      NULL     COMMENT '마지막으로 공개한 때',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (market_id),
    KEY idx_market_stories_status (status, updated_at),
    CONSTRAINT fk_market_stories_market FOREIGN KEY (market_id) REFERENCES markets (id) ON DELETE CASCADE,
    CONSTRAINT fk_market_stories_user FOREIGN KEY (updated_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='시장 이야기 (출처를 단 역사·유래)';

CREATE TABLE IF NOT EXISTS market_tour_info (
    market_id    BIGINT        NOT NULL COMMENT 'markets.id',
    status       VARCHAR(10)   NOT NULL COMMENT 'FOUND / NONE / FAILED',
    content_id   VARCHAR(20)   NULL     COMMENT '관광공사 콘텐츠 ID',
    title        VARCHAR(200)  NULL     COMMENT '관광공사에 등록된 이름',
    overview     TEXT          NULL     COMMENT '관광공사 개요 (원문 그대로, 줄바꿈만 정리)',
    fetched_at   DATETIME      NOT NULL,
    PRIMARY KEY (market_id),
    CONSTRAINT fk_market_tour_info_market FOREIGN KEY (market_id) REFERENCES markets (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='한국관광공사 시장 소개글 (국문 관광정보)';
