-- 영업 정보 제보 확인용 쿼리 (STO-01)
-- MySQL Workbench에서 필요한 쿼리만 골라 실행하세요. (관리자 화면이 생기기 전까지 사용)
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

-- 3) 제보를 처리한 뒤 상태 바꾸기 (예: 12번 제보를 반영함으로)
--    status: RECEIVED(접수됨) / APPLIED(반영됨) / REJECTED(반려됨)
--    admin_note는 제보한 사람의 '내 제보' 화면에 보여요.
-- UPDATE store_reports SET status = 'APPLIED', admin_note = '영업시간을 고쳤어요. 고마워요!' WHERE id = 12;
