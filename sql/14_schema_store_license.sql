-- 14. 백년가게 인허가 영업상태 확인 (DATA-02)
-- 행정안전부 인허가 데이터(일반음식점 등)에서 가게의 영업상태를 찾아 둔다. 가게 한 곳 = 한 행 (가장 최근 확인 결과).
--   result  OPEN       : 영업 중으로 나옴
--           CLOSED     : 폐업으로 나옴          → 관리자 '폐업 의심' 목록에 올림
--           SUSPENDED  : 휴업으로 나옴          → 관리자 '폐업 의심' 목록에 올림
--           NOT_FOUND  : 인허가 데이터에서 찾지 못함 (가게를 바꾸지 않음)
--           NOT_TARGET : 인허가로 확인할 수 없는 업종 (도소매, 공방 등)
--           ERROR      : API 오류 (다음 차례에 다시 확인)
--   review  NONE / PENDING(확인 대기) / CLOSED(관리자가 폐업 처리) / DISMISSED(관리자가 '영업 중 맞음')
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다.
USE ieum;

CREATE TABLE IF NOT EXISTS store_license_checks (
    store_id         BIGINT        NOT NULL,
    result           VARCHAR(12)   NOT NULL COMMENT 'OPEN / CLOSED / SUSPENDED / NOT_FOUND / NOT_TARGET / ERROR',
    license_kind     VARCHAR(40)   NULL COMMENT '찾은 인허가 종류 (예: general_restaurants)',
    license_id       VARCHAR(60)   NULL COMMENT '인허가 관리번호',
    license_name     VARCHAR(200)  NULL COMMENT '인허가의 사업장명',
    license_address  VARCHAR(300)  NULL COMMENT '인허가의 주소',
    license_status   VARCHAR(40)   NULL COMMENT '원본 영업상태 이름 그대로',
    closed_on        DATE          NULL COMMENT '폐업일자',
    match_note       VARCHAR(100)  NULL COMMENT '어떻게 맞췄는지 / 오류 내용',
    checked_at       DATETIME      NOT NULL,
    review           VARCHAR(10)   NOT NULL DEFAULT 'NONE' COMMENT 'NONE / PENDING / CLOSED / DISMISSED',
    reviewed_by      BIGINT        NULL COMMENT '확인한 관리자 (users.id)',
    reviewed_at      DATETIME      NULL,
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (store_id),
    KEY idx_store_license_review (review, checked_at),
    KEY idx_store_license_checked (checked_at),
    CONSTRAINT fk_store_license_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_store_license_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='백년가게 인허가 영업상태 확인 결과';
