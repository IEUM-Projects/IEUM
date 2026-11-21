-- 31. 관리자 작업 기록 (ADM-03)
--   admin_actions : 관리자가 바꾼 일(제보 처리, 글 가림, 회원 정지·탈퇴, 공지, 장소·시장 연결 등)을 한 줄씩 남긴다.
--                   '/api/admin/...' 로 들어온 바꾸기 요청(GET 제외) 가운데 성공한 것만 서버가 자동으로 적는다.
--     category    : USER 회원 | COMMUNITY 동네 이야기 | STORE 제보·가게·장소 | MARKET 시장 | NOTICE 공지 | DATA 자료 작업 | OTHER
--     action      : 무슨 일 (예: USER_WITHDRAW, POST_HIDE)
--     target_type : STORE | POST | COMMENT | USER | MARKET | NOTICE | FACILITY (없으면 NULL)
--     target_id   : 대상 번호 (시장 시설 코드처럼 글자도 있어 VARCHAR)
--     summary     : 한 줄 설명 (가린 까닭, 처리 결과 등. 300자까지)
--   1년이 지난 줄은 새벽 정리 때 지운다. 관리자 회원 줄과 묶지 않아(외래 키 없음) 관리자가 바뀌어도 기록은 남는다.
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다.
USE ieum;

CREATE TABLE IF NOT EXISTS admin_actions (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    admin_id     BIGINT       NOT NULL COMMENT '처리한 관리자 (users.id)',
    category     VARCHAR(12)  NOT NULL,
    action       VARCHAR(30)  NOT NULL,
    target_type  VARCHAR(12)  NULL,
    target_id    VARCHAR(40)  NULL,
    summary      VARCHAR(300) NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_admin_actions_cat (category, id),
    KEY idx_admin_actions_admin (admin_id, id),
    KEY idx_admin_actions_target (target_type, target_id, id),
    KEY idx_admin_actions_time (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '관리자 작업 기록 (ADM-03)';
