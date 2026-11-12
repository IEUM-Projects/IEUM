-- 07. 전통시장 표 (MKT-01)
-- 다른 표는 건드리지 않는다. 여러 번 실행해도 안전하다(IF NOT EXISTS).
USE ieum;

CREATE TABLE IF NOT EXISTS markets (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    source_key      VARCHAR(250)  NOT NULL COMMENT '같은 시장을 찾는 기준 (시장명|주소). 새 데이터를 넣을 때 갱신에 씀',
    name            VARCHAR(100)  NOT NULL,
    market_type     VARCHAR(20)   NOT NULL COMMENT '원본 시장유형 (상설장, 5일장, 상설장+5일장 ...)',
    is_permanent    TINYINT(1)    NOT NULL COMMENT '매일 여는 상설장인지',
    cycle_raw       VARCHAR(50)   NOT NULL COMMENT '원본 시장개설주기 (매일, 1일+6일 ...)',
    market_days     VARCHAR(20)   NULL     COMMENT '장 서는 날의 끝자리 (예: 1,6 → 1·6·11·16·21·26일, 0은 10·20·30일). 정기장이 없으면 NULL',
    road_address    VARCHAR(300)  NULL,
    jibun_address   VARCHAR(300)  NULL,
    latitude        DECIMAL(10,7) NULL,
    longitude       DECIMAL(10,7) NULL,
    geocode_status  VARCHAR(10)   NOT NULL COMMENT 'SOURCE(원본 좌표) / KAKAO(주소로 찾음) / FAILED / NONE',
    sido_code       CHAR(2)       NULL,
    sigungu_code    CHAR(5)       NULL,
    store_count     INT           NULL     COMMENT '점포 수',
    goods           VARCHAR(300)  NULL     COMMENT '취급품목 (농산물+축산물+...)',
    homepage_url    VARCHAR(300)  NULL,
    has_toilet      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '공중화장실 있음',
    has_parking     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '주차장 있음',
    opened_year     SMALLINT      NULL     COMMENT '개설연도',
    source          VARCHAR(60)   NOT NULL COMMENT '데이터 출처',
    data_date       DATE          NULL     COMMENT '원본 데이터 기준일',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_markets_source_key (source_key),
    KEY idx_markets_location (latitude, longitude),
    KEY idx_markets_region (sigungu_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='전통시장';
