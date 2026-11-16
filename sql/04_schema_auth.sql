-- 04. 회원 테이블 (SYS-01)
-- 기존 regions, stores 테이블과 데이터는 건드리지 않는다.
-- 여러 번 실행해도 안전하도록 IF NOT EXISTS 사용
USE ieum;

CREATE TABLE IF NOT EXISTS users (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    email              VARCHAR(100) NULL     COMMENT '이메일 (카카오 회원은 없을 수 있음)',
    password           VARCHAR(100) NULL     COMMENT 'BCrypt 해시 (소셜 회원은 없음)',
    nickname           VARCHAR(20)  NOT NULL,
    role               VARCHAR(10)  NOT NULL DEFAULT 'USER' COMMENT 'USER / RESIDENT / MERCHANT / ADMIN',
    provider           VARCHAR(10)  NOT NULL DEFAULT 'LOCAL' COMMENT 'LOCAL / KAKAO',
    provider_id        VARCHAR(50)  NULL     COMMENT '소셜 로그인 회원 번호',
    home_sigungu_code  CHAR(5)      NULL     COMMENT '거주 지역 (나중에 입력)',
    status             VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / SUSPENDED / WITHDRAWN',
    failed_login_count INT          NOT NULL DEFAULT 0,
    locked_until       DATETIME     NULL     COMMENT '로그인 잠금 해제 시각',
    last_login_at      DATETIME     NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email),
    UNIQUE KEY uk_users_nickname (nickname),
    UNIQUE KEY uk_users_provider (provider, provider_id),
    CONSTRAINT fk_users_region FOREIGN KEY (home_sigungu_code) REFERENCES regions (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='회원';

-- Refresh Token: 원본 대신 SHA-256 해시만 저장 (DB가 유출돼도 토큰으로 쓸 수 없음)
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id          BIGINT    NOT NULL AUTO_INCREMENT,
    user_id     BIGINT    NOT NULL,
    token_hash  CHAR(64)  NOT NULL COMMENT 'SHA-256 16진수',
    expires_at  DATETIME  NOT NULL,
    created_at  DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_tokens_hash (token_hash),
    KEY idx_refresh_tokens_user (user_id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='로그인 유지 토큰';
