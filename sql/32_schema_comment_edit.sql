-- 32. 댓글 고치기 (COM-04)
--   post_comments.edited_at : 글쓴이가 댓글을 마지막으로 고친 때. 있으면 화면에 '수정됨'을 붙인다 (없으면 NULL)
-- 다른 표의 데이터는 건드리지 않는다. 여러 번 실행해도 안전하다 (이미 있으면 건너뜀).
USE ieum;

-- MySQL은 "칸이 없을 때만 추가(ADD COLUMN IF NOT EXISTS)"를 지원하지 않아서,
-- information_schema로 먼저 확인하고 없을 때만 ALTER 문을 실행한다.
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE post_comments ADD COLUMN edited_at DATETIME NULL COMMENT ''글쓴이가 마지막으로 고친 때 (COM-04)'' AFTER created_at',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'post_comments' AND column_name = 'edited_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
