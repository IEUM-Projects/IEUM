-- 19. 로컬크리에이터 (LOC-01)
-- 로컬크리에이터는 가게(stores) 표에 type=CREATOR 로 들어간다 (처음 설계 그대로). 이 파일은 연도별 선정 내용 표만 만든다.
--   stores.category : 7대 분야 중 대표 분야 (가장 최근 선정의 첫 분야)
--   stores.info_status : 'UNVERIFIED' (지원사업 선정 목록이라 지금 운영 중인지는 확인하지 않음)
--   stores.area_sido / area_sigungu : 주소에서 뽑은 시도(약칭)·시군구 이름. 지역별 모아 보기에 쓴다
--     (regions 표에는 백년가게가 있는 시·군·구만 있어서, 로컬크리에이터는 이름으로 묶는다)
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다 (IF NOT EXISTS, 이미 있으면 건너뜀).
USE ieum;

CREATE TABLE IF NOT EXISTS creator_selections (
    store_id       BIGINT        NOT NULL COMMENT 'stores.id (type=CREATOR)',
    selected_year  SMALLINT      NOT NULL COMMENT '선정연도',
    item           VARCHAR(600)  NULL     COMMENT '아이템명 (무엇을 하는지, 원본 그대로)',
    fields         VARCHAR(100)  NULL     COMMENT '7대 분야 (쉼표로 구분, 정해진 순서)',
    organizer      VARCHAR(60)   NULL     COMMENT '주관기관',
    data_date      DATE          NOT NULL COMMENT '원본 파일 기준일',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (store_id, selected_year),
    CONSTRAINT fk_creator_selections_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='로컬크리에이터 연도별 선정 내용 (중소벤처기업부 로컬크리에이터 리스트)';

-- 지역별 모아 보기용 칸 (LOC-01)
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE stores ADD COLUMN area_sido VARCHAR(10) NULL COMMENT ''주소에서 뽑은 시도 약칭 (로컬크리에이터 지역별 보기)'' AFTER sigungu_code',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'stores' AND column_name = 'area_sido');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE stores ADD COLUMN area_sigungu VARCHAR(20) NULL COMMENT ''주소에서 뽑은 시군구 (일반구가 있는 시는 시까지)'' AFTER area_sido',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'stores' AND column_name = 'area_sigungu');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE stores ADD KEY idx_stores_area (type, area_sido, area_sigungu)',
    'DO 0')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'stores' AND index_name = 'idx_stores_area');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
