-- 29. 강제 탈퇴·재가입 막기 (ADM-02)
--   banned_identities : 관리자가 강제 탈퇴시킨 회원의 이메일 또는 카카오 회원번호를 '알아볼 수 없게 바꾼 값'(HMAC-SHA256)으로만 보관.
--                       같은 이메일·카카오 계정으로 다시 가입하는 것을 막는 데만 쓴다. 원래 값은 남기지 않는다.
--     kind          : EMAIL | KAKAO
--     identity_hash : HMAC-SHA256(서버 비밀 키, 소문자 이메일 또는 카카오 회원번호) 16진수 64자
--     user_id       : 탈퇴시킨 회원 줄 (탈퇴해도 줄은 남아 있음)
--     reason        : 관리자가 적은 사유 (관리자만 봄, 메일로 본인에게 보냄)
-- 관리자 화면 '재가입 허용'을 누르면 그 줄을 지운다.
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다.
USE ieum;

CREATE TABLE IF NOT EXISTS banned_identities (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    kind           VARCHAR(10)  NOT NULL COMMENT 'EMAIL | KAKAO',
    identity_hash  CHAR(64)     NOT NULL COMMENT 'HMAC-SHA256, 원래 값은 보관하지 않음',
    user_id        BIGINT       NOT NULL COMMENT '강제 탈퇴된 회원',
    reason         VARCHAR(300) NOT NULL COMMENT '관리자가 적은 사유',
    banned_by      BIGINT       NULL     COMMENT '처리한 관리자',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_banned_identity (kind, identity_hash),
    KEY idx_banned_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '강제 탈퇴·재가입 막기 (ADM-02)';
