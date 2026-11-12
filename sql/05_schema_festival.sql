-- 05. 축제 표 (FES-01)
-- 다른 표(stores, regions, users ...)는 건드리지 않는다. 여러 번 실행해도 안전하다(IF NOT EXISTS).
-- 내용은 서버가 공공데이터 API에서 받아서 채운다. 이 파일은 빈 표만 만든다.
USE ieum;

CREATE TABLE IF NOT EXISTS festivals (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    dedupe_key       VARCHAR(250)  NOT NULL COMMENT '같은 축제를 하나로 합치는 기준 (정리한 축제명|시작일)',
    source           VARCHAR(10)   NOT NULL COMMENT 'STANDARD(전국문화축제표준데이터) / TOUR(한국관광공사) / BOTH',
    name             VARCHAR(200)  NOT NULL,
    place            VARCHAR(300)  NULL     COMMENT '개최장소',
    start_date       DATE          NOT NULL,
    end_date         DATE          NOT NULL,
    description      TEXT          NULL     COMMENT '축제 내용',
    organizer        VARCHAR(200)  NULL     COMMENT '주관기관',
    host             VARCHAR(200)  NULL     COMMENT '주최기관',
    sponsor          VARCHAR(300)  NULL     COMMENT '후원기관',
    phone            VARCHAR(50)   NULL,
    homepage_url     VARCHAR(500)  NULL,
    road_address     VARCHAR(300)  NULL,
    jibun_address    VARCHAR(300)  NULL,
    latitude         DECIMAL(10,7) NULL,
    longitude        DECIMAL(10,7) NULL,
    geocode_status   VARCHAR(10)   NOT NULL COMMENT 'SOURCE(원본 좌표) / KAKAO(주소로 찾음) / FAILED / NONE',
    sido_code        CHAR(2)       NULL,
    sigungu_code     CHAR(5)       NULL,
    image_url        VARCHAR(500)  NULL     COMMENT '대표 사진 (한국관광공사)',
    image_license    VARCHAR(10)   NULL     COMMENT '사진 이용 조건 (Type1, Type3 ...)',
    tour_content_id  VARCHAR(20)   NULL     COMMENT '한국관광공사 콘텐츠 번호',
    tour_detail_at   DATETIME      NULL     COMMENT '관광공사 상세(소개글)를 받아 온 시각',
    provider         VARCHAR(100)  NULL     COMMENT '정보를 올린 기관',
    data_date        DATE          NULL     COMMENT '원본 데이터 기준일',
    last_seen_at     DATETIME      NOT NULL COMMENT '마지막으로 API에서 확인한 시각',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_festivals_dedupe (dedupe_key),
    KEY idx_festivals_dates (start_date, end_date),
    KEY idx_festivals_sido (sido_code, start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='지역 축제';
