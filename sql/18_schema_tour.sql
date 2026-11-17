-- 18. 주변 관광지 (TOUR-01)
-- 한국관광공사 국문 관광정보(위치 기반 조회)에서 받은 관광지·문화시설을 저장해 두고 다시 쓴다.
--   tour_spots        : 관광지 한 곳 = 한 행 (관광공사 contentid)
--   tour_area_fetches : 어느 칸(약 2km 네모)을 언제 받았는지. 7일 안에 받은 칸은 다시 부르지 않는다
-- 관광공사 API는 하루 호출 수가 정해져 있고 느릴 수 있어서, 같은 동네는 저장해 둔 것을 쓴다.
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다.
USE ieum;

CREATE TABLE IF NOT EXISTS tour_spots (
    content_id     VARCHAR(20)    NOT NULL COMMENT '관광공사 contentid',
    content_type   SMALLINT       NOT NULL COMMENT '12 관광지, 14 문화시설',
    title          VARCHAR(200)   NOT NULL,
    address        VARCHAR(300)   NULL,
    latitude       DECIMAL(10,7)  NOT NULL,
    longitude      DECIMAL(10,7)  NOT NULL,
    image_url      VARCHAR(500)   NULL COMMENT '대표 사진 (관광공사)',
    image_license  VARCHAR(10)    NULL COMMENT '공공누리 유형 (Type1 / Type3)',
    fetched_at     DATETIME       NOT NULL,
    PRIMARY KEY (content_id),
    KEY idx_tour_spots_location (latitude, longitude)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='주변 관광지 (한국관광공사)';

CREATE TABLE IF NOT EXISTS tour_area_fetches (
    cell_key     VARCHAR(40)    NOT NULL COMMENT '위도칸:경도칸:종류',
    fetched_at   DATETIME       NOT NULL,
    spot_count   INT            NOT NULL,
    PRIMARY KEY (cell_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='관광공사에서 받은 동네 칸';
