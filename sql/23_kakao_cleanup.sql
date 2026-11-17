-- 23. 카카오 로컬 API 이용 조건 맞추기 (DATA-05)
-- 카카오 로컬 API 결과는 '장소 ID'만 저장할 수 있다 (좌표·주소·전화번호·카테고리 같은 응답값과 그것으로 만든 값은 저장 불가, 이미 저장한 것은 지워야 함).
-- 이 파일은 카카오에서 온 값을 한 번만 지운다:
--   가게(백년가게·로컬크리에이터) : 좌표, 카카오로 정리한 도로명·지번 주소, 법정동·시도·시군구 코드, 전화번호(관리자가 직접 고친 번호는 남김),
--                                  카카오 카테고리로 정한 분류(백년가게), 카카오에서 찾았다고 정한 '영업 확인됨'(관리자가 정한 것은 남김),
--                                  카카오 매칭 상태 → 장소 ID만 남김
--   시장 : 카카오로 찾은 좌표(geocode_status='KAKAO'), 카카오로 정한 시도·시군구 코드
--   축제 : 카카오로 찾은 좌표(geocode_status='KAKAO'), 카카오로 정한 시도·시군구 코드
-- 지운 좌표·지역은 서버를 켜면 브이월드(국토교통부 공간정보 오픈플랫폼) 주소 검색으로 다시 채운다 (몇 분 걸림).
-- 백년가게 분류·영업 확인은 공공 인허가 확인 결과(DATA-02)로 다시 채운다: 일반음식점 → 음식점, 휴게음식점·제과점 → 카페·간식,
-- 인허가 '영업 중' → 영업 확인됨. 나머지는 '분류 없음'(관리자 화면에서 지정).
-- 여러 번 실행해도 안전하다: 지우기는 ieum_data_fixes 표에 기록해 두고 한 번만 한다 (두 번째부터는 건너뜀).
-- UPDATE 가 있어서 Workbench 안전 모드를 잠깐 끈다.
USE ieum;
SET @old_safe_updates = @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;

CREATE TABLE IF NOT EXISTS ieum_data_fixes (
    name        VARCHAR(60)  NOT NULL,
    applied_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='한 번만 하는 데이터 정리 기록';

SET @todo = (SELECT COUNT(*) = 0 FROM ieum_data_fixes WHERE name = '23_kakao_cleanup');

-- 1) 가게: 카카오에서 온 값 지우기 (장소 ID는 남김)
SET @sql = IF(@todo, '
UPDATE stores s
SET s.latitude = NULL, s.longitude = NULL, s.b_code = NULL, s.sido_code = NULL, s.sigungu_code = NULL,
    s.geocode_status = ''PENDING'',
    s.road_address = IF(s.type = ''CENTURY'', s.address_raw, s.road_address),
    s.jibun_address = NULL,
    s.phone = IF(EXISTS (SELECT 1 FROM store_edit_logs l WHERE l.store_id = s.id AND l.field = ''PHONE''), s.phone, NULL),
    s.category = IF(s.type = ''CENTURY'', NULL, s.category),
    s.info_status = IF(s.info_status = ''VERIFIED''
                       AND NOT EXISTS (SELECT 1 FROM store_edit_logs l WHERE l.store_id = s.id AND l.field = ''INFO_STATUS''),
                       ''UNVERIFIED'', s.info_status),
    s.enrich_status = CASE WHEN s.kakao_place_id IS NULL THEN ''PENDING''
                           WHEN s.enrich_status = ''LOW_CONFIDENCE'' THEN ''REVIEW''
                           ELSE ''LINKED'' END', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) 시장: 카카오로 찾은 좌표, 카카오로 정한 지역 코드
SET @sql = IF(@todo, '
UPDATE markets SET latitude = IF(geocode_status = ''KAKAO'', NULL, latitude),
                   longitude = IF(geocode_status = ''KAKAO'', NULL, longitude),
                   geocode_status = IF(geocode_status IN (''KAKAO'', ''FAILED''), ''NONE'', geocode_status),
                   sido_code = NULL, sigungu_code = NULL', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) 축제: 카카오로 찾은 좌표, 카카오로 정한 지역 코드
SET @sql = IF(@todo, '
UPDATE festivals SET latitude = IF(geocode_status = ''KAKAO'', NULL, latitude),
                     longitude = IF(geocode_status = ''KAKAO'', NULL, longitude),
                     geocode_status = IF(geocode_status IN (''KAKAO'', ''FAILED''), ''NONE'', geocode_status),
                     sido_code = NULL, sigungu_code = NULL', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO ieum_data_fixes (name) VALUES ('23_kakao_cleanup');

-- 4) 백년가게 분류·영업 확인: 공공 인허가 확인 결과(DATA-02)로 채우기 (여러 번 실행해도 같음, 인허가 파일을 올릴 때마다 서버도 같은 일을 함)
--    분류가 비어 있는 곳만, '영업 중'인 인허가 업종으로: 일반음식점 → 음식점, 휴게음식점·제과점 → 카페·간식
UPDATE stores s
JOIN store_license_checks c ON c.store_id = s.id
SET s.category = CASE WHEN c.license_kind IN ('general_restaurants', '일반음식점') THEN '음식점'
                      WHEN c.license_kind IN ('rest_restaurants', 'bakeries', '휴게음식점', '제과점') THEN '카페·간식' END
WHERE s.type = 'CENTURY' AND s.category IS NULL
  AND c.result = 'OPEN'
  AND c.license_kind IN ('general_restaurants', 'rest_restaurants', 'bakeries', '일반음식점', '휴게음식점', '제과점');

--    인허가에서 '영업 중'으로 확인된 가게 → 영업 확인됨 (관리자가 직접 정한 상태는 건드리지 않음)
UPDATE stores s
JOIN store_license_checks c ON c.store_id = s.id AND c.result = 'OPEN'
SET s.info_status = 'VERIFIED'
WHERE s.info_status = 'UNVERIFIED'
  AND NOT EXISTS (SELECT 1 FROM store_edit_logs l WHERE l.store_id = s.id AND l.field = 'INFO_STATUS');

SET SQL_SAFE_UPDATES = @old_safe_updates;
