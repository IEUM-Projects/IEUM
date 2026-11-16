-- 11. 로그인 유지 토큰 묶음 (SYS-02)
-- 한 번 로그인해서 이어지는 토큰들(재발급으로 바뀐 토큰 포함)에 같은 family_id를 붙인다.
-- 로그아웃하면 같은 family의 토큰을 모두 지워서, 로그아웃 순간 재발급이 겹쳐도 로그인이 남지 않게 한다.
-- 이미 있는 토큰은 family_id가 비어 있고(NULL), 다음 재발급 때 채워진다. 여러 번 실행해도 안전하다.
USE ieum;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE refresh_tokens ADD COLUMN family_id CHAR(36) NULL COMMENT ''같은 로그인에서 이어진 토큰 묶음'' AFTER user_id',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'refresh_tokens' AND column_name = 'family_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE refresh_tokens ADD KEY idx_refresh_tokens_family (family_id)',
    'DO 0')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'refresh_tokens' AND index_name = 'idx_refresh_tokens_family');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
