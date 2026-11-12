-- 01. 이음(IEUM) 기본 테이블: 지역 코드, 가게
-- MySQL 8 / utf8mb4
-- 여러 번 실행해도 안전하다: 표가 이미 있으면 그대로 둔다 (데이터를 지우지 않음)

CREATE DATABASE IF NOT EXISTS ieum DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ieum;

-- 지역 코드 (법정동 코드 앞 5자리 = 시·군·구)
CREATE TABLE IF NOT EXISTS regions (
    code          CHAR(5)      NOT NULL COMMENT '시·군·구 코드 (법정동 코드 앞 5자리)',
    sido_code     CHAR(2)      NOT NULL COMMENT '시·도 코드 (법정동 코드 앞 2자리)',
    sido_name     VARCHAR(20)  NOT NULL COMMENT '시·도 이름 (짧은 이름, 예: 서울)',
    sigungu_name  VARCHAR(40)  NOT NULL COMMENT '시·군·구 이름 (예: 수원시 장안구)',
    PRIMARY KEY (code),
    KEY idx_regions_sido (sido_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='지역 코드';

-- 가게 (백년가게 + 로컬크리에이터)
CREATE TABLE IF NOT EXISTS stores (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    type            VARCHAR(10)   NOT NULL COMMENT 'CENTURY(백년가게) / CREATOR(로컬크리에이터)',
    name            VARCHAR(100)  NOT NULL,
    category        VARCHAR(20)   NULL     COMMENT '음식점, 카페·간식, 도소매, 생활서비스, 공방·공예, 기타',
    phone           VARCHAR(30)   NULL,
    hours           VARCHAR(200)  NULL     COMMENT '영업시간 (상인 입력)',
    history         TEXT          NULL     COMMENT '가게 역사 (상인 입력)',
    certified_year  SMALLINT      NULL     COMMENT '백년가게 지정 연도',
    sns_url         VARCHAR(300)  NULL,
    owner_id        BIGINT        NULL     COMMENT '매장 관리 권한이 있는 상인 회원',
    source          VARCHAR(50)   NOT NULL COMMENT '데이터 출처',
    source_no       INT           NULL     COMMENT '원본 데이터 연번',
    address_raw     VARCHAR(300)  NULL     COMMENT '원본 주소',
    road_address    VARCHAR(300)  NULL,
    jibun_address   VARCHAR(300)  NULL,
    latitude        DECIMAL(10,7) NULL,
    longitude       DECIMAL(10,7) NULL,
    sido_code       CHAR(2)       NULL,
    sigungu_code    CHAR(5)       NULL,
    b_code          CHAR(10)      NULL     COMMENT '법정동 코드 10자리',
    kakao_place_id  VARCHAR(20)   NULL,
    geocode_status  VARCHAR(15)   NOT NULL COMMENT 'OK / NEEDS_REVIEW / FAILED',
    enrich_status   VARCHAR(15)   NOT NULL COMMENT 'MATCHED / LOW_CONFIDENCE / NOT_FOUND',
    info_status     VARCHAR(12)   NOT NULL COMMENT 'VERIFIED / UNVERIFIED / CLOSED',
    is_visible      TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '지도 노출 여부',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stores_source (source, source_no),
    KEY idx_stores_location (latitude, longitude),
    KEY idx_stores_region (sigungu_code),
    KEY idx_stores_filter (is_visible, type, category),
    CONSTRAINT fk_stores_region FOREIGN KEY (sigungu_code) REFERENCES regions (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='가게';
