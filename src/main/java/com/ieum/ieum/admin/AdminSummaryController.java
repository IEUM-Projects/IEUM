package com.ieum.ieum.admin;

import com.ieum.ieum.admin.log.AdminActionService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자 첫 화면 요약 (ADM-03) + 작업 기록 보기
 *   GET /api/admin/summary                  처리할 일, 최근 7일 숫자, 최근 관리자 작업 8줄
 *   GET /api/admin/actions?category=&adminId=&targetType=&targetId=&page=   작업 기록 (50줄씩)
 * 모두 관리자만 (SecurityConfig 의 /api/admin/** 규칙).
 */
@RestController
@RequiredArgsConstructor
public class AdminSummaryController {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final NamedParameterJdbcTemplate jdbc;
    private final AdminActionService actions;

    /** 처리할 일 하나: 화면에 보여 줄 이름, 건수, 누르면 갈 곳, 더 설명 */
    public record Todo(String key, String label, long count, String url, String note) {
    }

    /** 하루 숫자 */
    public record Day(LocalDate date, long signups, long withdrawals, long posts, long comments, long storeReports, long communityReports) {
    }

    public record Summary(List<Todo> todos, long todoTotal, List<Day> days, Map<String, Long> totals,
                          List<AdminActionService.Row> recent) {
    }

    @GetMapping("/api/admin/summary")
    @Transactional(readOnly = true)
    public Summary summary() {
        LocalDate today = LocalDate.now(SEOUL);
        MapSqlParameterSource p = new MapSqlParameterSource("today", Date.valueOf(today));
        List<Todo> todos = new ArrayList<>();
        long pendingStores = count("SELECT COUNT(DISTINCT store_id) FROM store_reports WHERE status = 'RECEIVED'", p);
        long received = count("SELECT COUNT(*) FROM store_reports WHERE status = 'RECEIVED'", p);
        todos.add(new Todo("STORE_REPORTS", "영업 정보 제보", received, "/admin.html",
                received == 0 ? null : "가게 " + pendingStores + "곳"));
        todos.add(new Todo("COMMUNITY_REPORTS", "동네 이야기 신고", count("""
                SELECT COUNT(*) FROM (SELECT DISTINCT target_type, target_id FROM community_reports WHERE status = 'RECEIVED') x""", p),
                "/admin-community.html", "글·댓글 수"));
        todos.add(new Todo("TEMP_EXPIRED", "임시 숨김 30일 지남", count(
                "SELECT COUNT(*) FROM posts WHERE status = 'TEMP_HIDDEN' AND hidden_until IS NOT NULL AND hidden_until <= :today", p),
                "/admin-community.html#HIDDEN", "다시 보일지 정해 주세요"));
        todos.add(new Todo("RECOMMEND", "가게 추천 확인 전", count(
                "SELECT COUNT(*) FROM posts WHERE category = 'RECOMMEND' AND status = 'VISIBLE' AND recommend_status = 'PENDING'", p),
                "/admin-community.html#RECOMMEND", null));
        todos.add(new Todo("CLOSURES", "폐업 의심 확인", count("SELECT COUNT(*) FROM store_license_checks WHERE review = 'PENDING'", p),
                "/admin-closures.html", null));
        todos.add(new Todo("MARKET_LINKS", "시장 연결 확인", count("SELECT COUNT(*) FROM market_facilities WHERE link_status = 'PENDING'", p),
                "/admin-markets.html", null));
        todos.add(new Todo("STORY_DRAFTS", "공개 전 시장 이야기", count("SELECT COUNT(*) FROM market_stories WHERE status = 'DRAFT'", p),
                "/admin-stories.html", null));
        long todoTotal = todos.stream().mapToLong(Todo::count).sum();

        // 최근 7일 (오늘 포함). 날짜별로 한 번씩 모아 센다
        LocalDate from = today.minusDays(6);
        MapSqlParameterSource d = new MapSqlParameterSource("from", Date.valueOf(from));
        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) byDay.put(today.minusDays(i), new long[6]);
        String[] sqls = {
                "SELECT DATE(created_at) AS d, COUNT(*) AS n FROM users WHERE created_at >= :from GROUP BY d",
                // 탈퇴한 때를 따로 적지 않아, 탈퇴로 바뀐 마지막 수정 시각으로 센다 (그 뒤 관리자 처리가 있으면 그날로 옮겨질 수 있음)
                "SELECT DATE(updated_at) AS d, COUNT(*) AS n FROM users WHERE status = 'WITHDRAWN' AND updated_at >= :from GROUP BY d",
                "SELECT DATE(created_at) AS d, COUNT(*) AS n FROM posts WHERE created_at >= :from GROUP BY d",
                "SELECT DATE(created_at) AS d, COUNT(*) AS n FROM post_comments WHERE created_at >= :from GROUP BY d",
                "SELECT DATE(created_at) AS d, COUNT(*) AS n FROM store_reports WHERE created_at >= :from GROUP BY d",
                "SELECT DATE(created_at) AS d, COUNT(*) AS n FROM community_reports WHERE created_at >= :from GROUP BY d",
        };
        for (int k = 0; k < sqls.length; k++) {
            int col = k;
            jdbc.query(sqls[k], d, rs -> {
                long[] row = byDay.get(rs.getDate("d").toLocalDate());
                if (row != null) row[col] = rs.getLong("n");
            });
        }
        List<Day> days = byDay.entrySet().stream()
                .map(e -> new Day(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3], e.getValue()[4], e.getValue()[5]))
                .toList();

        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("members", count("SELECT COUNT(*) FROM users WHERE status <> 'WITHDRAWN'", p));
        totals.put("suspended", count("SELECT COUNT(*) FROM users WHERE status = 'SUSPENDED'", p));
        totals.put("posts", count("SELECT COUNT(*) FROM posts WHERE status = 'VISIBLE'", p));
        totals.put("hidden", count("SELECT COUNT(*) FROM posts WHERE status IN ('HIDDEN', 'TEMP_HIDDEN')", p));

        return new Summary(todos, todoTotal, days, totals, actions.recent(8));
    }

    @GetMapping("/api/admin/actions")
    public AdminActionService.Page actions(@RequestParam(required = false) String category, @RequestParam(required = false) Long adminId,
                                           @RequestParam(required = false) String targetType, @RequestParam(required = false) String targetId,
                                           @RequestParam(defaultValue = "0") int page) {
        return actions.list(category, adminId, targetType, targetId, page);
    }

    private long count(String sql, MapSqlParameterSource p) {
        Long n = jdbc.queryForObject(sql, p, Long.class);
        return n == null ? 0 : n;
    }
}
