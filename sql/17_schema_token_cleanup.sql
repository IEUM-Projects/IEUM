-- 17. 로그인 유지 토큰 정리용 색인 (SYS-03)
-- 서버가 매일 새벽 만료된 토큰(refresh_tokens.expires_at이 지난 것)을 지운다.
-- 만료 시각으로 바로 찾도록 색인을 둔다 (없으면 표 전체를 훑으며 지워서, 그동안 로그인 유지 요청이 기다릴 수 있음).
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다 (이미 있으면 건너뜀).
USE ieum;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE refresh_tokens ADD INDEX idx_refresh_tokens_expires (expires_at)',
    'DO 0')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'refresh_tokens' AND index_name = 'idx_refresh_tokens_expires');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
