-- 15. 온누리상품권 가맹 정보 (DATA-04)
-- 소상공인시장진흥공단 '전국 온누리상품권 가맹점 현황'(1년에 한 번)에서
--   store_onnuri  : 백년가게와 맞춘 가맹점 (가게 한 곳 = 한 행)
--   market_onnuri : 전통시장별 가맹점 수 (시장 한 곳 = 한 행)
-- 원본에는 주소가 시·도까지만 있어서, 가게는 '이름 + 소속 시장'으로 확실한 것만 맞춘다 (16번 SQL 머리말 참고).
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다.
USE ieum;

CREATE TABLE IF NOT EXISTS store_onnuri (
    store_id         BIGINT        NOT NULL,
    merchant_name    VARCHAR(200)  NOT NULL COMMENT '원본 가맹점명',
    market_name      VARCHAR(200)  NULL COMMENT '원본 소속 시장명(또는 상점가)',
    paper            TINYINT(1)    NOT NULL COMMENT '지류(종이) 상품권 가맹',
    digital          TINYINT(1)    NOT NULL COMMENT '디지털(카드·모바일) 상품권 가맹',
    registered_year  SMALLINT      NULL COMMENT '가맹 등록년도',
    match_note       VARCHAR(60)   NOT NULL COMMENT '어떻게 맞췄는지',
    data_date        DATE          NOT NULL COMMENT '원본 기준일',
    PRIMARY KEY (store_id),
    CONSTRAINT fk_store_onnuri_store FOREIGN KEY (store_id) REFERENCES stores (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='백년가게 온누리상품권 가맹 정보';

CREATE TABLE IF NOT EXISTS market_onnuri (
    market_id        BIGINT        NOT NULL,
    merchant_count   INT           NOT NULL COMMENT '가맹점 수',
    digital_count    INT           NOT NULL COMMENT '그중 디지털 상품권 가맹점 수',
    data_date        DATE          NOT NULL COMMENT '원본 기준일',
    PRIMARY KEY (market_id),
    CONSTRAINT fk_market_onnuri_market FOREIGN KEY (market_id) REFERENCES markets (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='전통시장 온누리상품권 가맹점 수';
