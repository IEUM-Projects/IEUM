-- 영업 정보 제보 확인용 쿼리 (STO-01)
-- MySQL Workbench에서 필요한 쿼리만 골라 실행하세요.
-- 제보 처리(반영·반려)는 관리자 화면(/admin.html)에서 해요. 처리한 관리자와 시각이 함께 기록돼요.
USE ieum;

-- 1) 아직 처리하지 않은 제보 (오래된 것부터)
SELECT r.id, r.created_at, s.id AS store_id, s.name AS store_name, r.report_type, r.content, u.nickname
FROM store_reports r
JOIN stores s ON s.id = r.store_id
JOIN users u ON u.id = r.user_id
WHERE r.status = 'RECEIVED'
ORDER BY r.created_at;

-- 2) 확인이 필요한 가게: 최근 90일 동안 서로 다른 회원 3명 이상이 '폐업했어요'로 제보한 가게
--    (자동으로 숨기지 않는다. 직접 확인한 뒤 결정)
SELECT s.id, s.name, s.road_address, COUNT(DISTINCT r.user_id) AS reporters, MAX(r.created_at) AS last_report
FROM store_reports r
JOIN stores s ON s.id = r.store_id
WHERE r.report_type = 'CLOSED'
  AND r.status = 'RECEIVED'
  AND r.created_at >= NOW() - INTERVAL 90 DAY
GROUP BY s.id, s.name, s.road_address
HAVING reporters >= 3
ORDER BY reporters DESC;

-- 3) 관리자 지정 (ADM-01): 이메일을 실제 값으로 바꿔서 실행
--    지정한 뒤 그 계정으로 '로그아웃 → 다시 로그인'해야 관리자 화면이 열려요.
-- UPDATE users SET role = 'ADMIN' WHERE email = '내이메일@example.com';
--    카카오로 가입한 계정은 이메일이 없으니 닉네임으로:
-- UPDATE users SET role = 'ADMIN' WHERE nickname = '내닉네임' AND provider = 'KAKAO';

-- 4) 관리자 해제: 바로 막혀요 (관리자 API가 요청마다 DB의 권한을 다시 확인)
-- UPDATE users SET role = 'USER' WHERE email = '내이메일@example.com';

-- 5) 지금 관리자 목록
SELECT id, email, nickname, provider, status, last_login_at FROM users WHERE role = 'ADMIN';

-- 6) 최근 가게 정보 수정 기록 (누가, 어떤 가게의, 무엇을, 무엇에서 무엇으로)
SELECT l.created_at, u.nickname AS admin, s.id AS store_id, s.name AS store_name, l.field, l.before_value, l.after_value
FROM store_edit_logs l
JOIN stores s ON s.id = l.store_id
JOIN users u ON u.id = l.admin_id
ORDER BY l.created_at DESC, l.id DESC
LIMIT 100;
