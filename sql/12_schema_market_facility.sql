-- 12. 전통시장 시설 정보 (DATA-01)
-- - markets.market_code: 소상공인시장진흥공단 '전통시장현황'의 시장코드 (한 번 이어 두면 다음 해에는 코드로 바로 갱신)
-- - market_facilities: 전통시장현황 CSV 한 줄 = 한 행. 방문자에게 쓸모 있는 시설 12가지만 저장
--     link_status  AUTO    : 시장명 + 주소가 같아서 자동으로 이음
--                  PENDING : 이름은 같은데 주소가 달라서 관리자 확인이 필요함
--                  LINKED  : 관리자가 확인하고 이음
--                  IGNORED : 관리자가 "이 시장이 아님"으로 처리함
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다 (이미 있으면 건너뜀).
USE ieum;

-- 1) 시장코드 칸
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE markets ADD COLUMN market_code VARCHAR(20) NULL COMMENT ''전통시장현황 시장코드 (market_facilities.market_code)'' AFTER source_key',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'markets' AND column_name = 'market_code');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) 한 시장코드는 한 시장에만
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE markets ADD UNIQUE KEY uk_markets_market_code (market_code)',
    'DO 0')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'markets' AND index_name = 'uk_markets_market_code');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) 시설 정보 (값: 1 있음, 0 없음, NULL 정보 없음)
CREATE TABLE IF NOT EXISTS market_facilities (
    market_code          VARCHAR(20)   NOT NULL COMMENT '시장코드',
    name                 VARCHAR(100)  NOT NULL COMMENT '원본의 시장명',
    road_address         VARCHAR(300)  NULL,
    jibun_address        VARCHAR(300)  NULL,
    sido                 VARCHAR(20)   NULL COMMENT '원본의 시도 (예: 경북)',
    sigungu              VARCHAR(30)   NULL COMMENT '원본의 시군구',
    arcade               TINYINT(1)    NULL COMMENT '아케이드',
    elevator             TINYINT(1)    NULL COMMENT '엘리베이터·에스컬레이터',
    help_center          TINYINT(1)    NULL COMMENT '고객지원센터',
    kids_room            TINYINT(1)    NULL COMMENT '유아놀이방',
    rest_area            TINYINT(1)    NULL COMMENT '고객휴게실',
    nursing_room         TINYINT(1)    NULL COMMENT '수유센터',
    lockers              TINYINT(1)    NULL COMMENT '물품보관함',
    bike_rack            TINYINT(1)    NULL COMMENT '자전거보관함',
    shopping_cart        TINYINT(1)    NULL COMMENT '쇼핑카트',
    foreign_desk         TINYINT(1)    NULL COMMENT '외국인 안내센터',
    customer_parking     TINYINT(1)    NULL COMMENT '시장 전용 고객주차장',
    aed                  TINYINT(1)    NULL COMMENT '자동심장충격기',
    source               VARCHAR(60)   NOT NULL,
    data_date            DATE          NOT NULL COMMENT '원본 파일 기준일',
    link_status          VARCHAR(10)   NOT NULL DEFAULT 'PENDING' COMMENT 'AUTO / PENDING / LINKED / IGNORED',
    suggested_market_id  BIGINT        NULL COMMENT '이름이 같은 시장 (관리자 확인용)',
    reviewed_by          BIGINT        NULL COMMENT '확인한 관리자 (users.id)',
    reviewed_at          DATETIME      NULL,
    created_at           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (market_code),
    KEY idx_market_facilities_status (link_status, name),
    CONSTRAINT fk_market_facilities_suggested FOREIGN KEY (suggested_market_id) REFERENCES markets (id) ON DELETE SET NULL,
    CONSTRAINT fk_market_facilities_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='전통시장 시설 정보 (소상공인시장진흥공단 전통시장현황)';
