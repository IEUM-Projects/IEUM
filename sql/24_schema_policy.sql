-- 24. 이용약관·개인정보 처리방침 동의 기록 (POL-01)
--   users.agreed_policy_version : 동의한 약관·방침 판 (0 = 아직 동의 안 함)
--   users.agreed_at             : 동의한 때
-- 약관·방침 판은 application.yml 의 ieum.policy.version 이다. 판을 올리면 다음 로그인 때 다시 동의를 받는다.
-- 이 파일을 실행하기 전에 가입한 회원(카카오 포함)은 0 이라서, 다음에 로그인하면 동의 화면이 먼저 나온다.
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다 (이미 있으면 건너뜀).
USE ieum;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE users ADD COLUMN agreed_policy_version INT NOT NULL DEFAULT 0 COMMENT ''동의한 이용약관·개인정보 처리방침 판 (0 = 동의 전)'' AFTER home_sigungu_code',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'agreed_policy_version');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE users ADD COLUMN agreed_at DATETIME NULL COMMENT ''동의한 때 (이용약관, 개인정보 수집·이용, 만 14세 이상)'' AFTER agreed_policy_version',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'agreed_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
