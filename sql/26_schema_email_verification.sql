-- 26. 가입 이메일 인증 (AUTH-02)
--   email_verifications : 가입 화면에서 보낸 6자리 인증번호 (번호는 암호화해서 저장, 하루 지나면 자동 삭제)
--   users.email_verified_at : 이메일을 인증한 때 (카카오 회원은 비움)
-- 이 파일을 실행하기 전에 이메일로 가입한 회원은 '인증됨'으로 처리한다 (가입한 때를 인증한 때로).
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다 (이미 있으면 건너뜀).
USE ieum;

CREATE TABLE IF NOT EXISTS email_verifications (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    email        VARCHAR(100) NOT NULL COMMENT '인증번호를 받은 이메일',
    purpose      VARCHAR(20)  NOT NULL DEFAULT 'SIGNUP' COMMENT 'SIGNUP (나중에 비밀번호 찾기 등)',
    code_hash    VARCHAR(100) NOT NULL COMMENT '6자리 인증번호 (BCrypt)',
    attempts     INT          NOT NULL DEFAULT 0 COMMENT '틀린 횟수 (5번이면 이 번호는 못 씀)',
    request_ip   VARCHAR(45)  NULL COMMENT '요청한 IP (같은 곳에서 너무 많이 보내는 것 막기)',
    expires_at   DATETIME     NOT NULL COMMENT '번호를 입력할 수 있는 마지막 때 (보낸 뒤 10분)',
    verified_at  DATETIME     NULL COMMENT '번호가 맞은 때 (그 뒤 30분 안에 가입)',
    used_at      DATETIME     NULL COMMENT '가입에 쓴 때 (한 번만 쓸 수 있음)',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_email_verifications_email (email, purpose, created_at),
    KEY idx_email_verifications_ip (request_ip, created_at),
    KEY idx_email_verifications_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '가입 이메일 인증번호 (AUTH-02)';

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE users ADD COLUMN email_verified_at DATETIME NULL COMMENT ''이메일을 인증한 때 (AUTH-02, 카카오 회원은 NULL)'' AFTER email',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'email_verified_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 이미 이메일로 가입한 회원은 인증된 것으로 (배포 전이라 운영자가 직접 만든 계정뿐)
SET SQL_SAFE_UPDATES = 0;
UPDATE users SET email_verified_at = created_at
 WHERE provider = 'LOCAL' AND email IS NOT NULL AND email_verified_at IS NULL;
SET SQL_SAFE_UPDATES = 1;
