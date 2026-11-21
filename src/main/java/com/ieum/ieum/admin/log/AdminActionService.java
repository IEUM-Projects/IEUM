package com.ieum.ieum.admin.log;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자 작업 기록 (ADM-03): 남기기, 보기, 1년 지난 기록 정리.
 * 남기기는 AdminActionFilter 가 관리자 API 성공 응답마다 부른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminActionService {

    public static final int PAGE_SIZE = 50;
    static final Set<String> CATEGORIES = Set.of("USER", "COMMUNITY", "STORE", "MARKET", "NOTICE", "DATA", "OTHER");

    private final NamedParameterJdbcTemplate jdbc;

    /** 대상: 이름(지금 기준)과 관리자가 눌러 볼 주소. 없어진 대상이면 이름 대신 번호만 */
    public record Target(String type, String id, String name, String url) {
    }

    public record Row(long id, LocalDateTime at, long adminId, String admin, String category, String categoryLabel,
                      String action, String label, Target target, String summary) {
    }

    public record Page(List<Row> items, int page, boolean hasNext, Map<String, String> categories, List<AdminOption> admins) {
    }

    public record AdminOption(long id, String nickname) {
    }

    void record(long adminId, AdminActionRoutes.Entry e) {
        String summary = e.summary();
        if (summary != null) {
            summary = summary.replaceAll("\\s+", " ").strip();
            if (summary.length() > 300) summary = summary.substring(0, 299) + "…";
        }
        jdbc.update("""
                INSERT INTO admin_actions (admin_id, category, action, target_type, target_id, summary)
                VALUES (:admin, :cat, :action, :tt, :tid, :summary)""",
                new MapSqlParameterSource("admin", adminId).addValue("cat", e.category()).addValue("action", e.action())
                        .addValue("tt", e.targetType()).addValue("tid", e.targetId() == null ? null : truncate(e.targetId(), 40))
                        .addValue("summary", summary));
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** 목록: 최근 순 50줄씩. category·adminId·대상(targetType+targetId)으로 좁힐 수 있다 */
    @Transactional(readOnly = true)
    public Page list(String category, Long adminId, String targetType, String targetId, int page) {
        String cat = category == null ? "" : category.strip().toUpperCase();
        if (!cat.isEmpty() && !CATEGORIES.contains(cat)) throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("분류를 확인해 주세요."));
        int p = Math.max(0, page);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", PAGE_SIZE + 1).addValue("offset", p * PAGE_SIZE);
        if (!cat.isEmpty()) { where.append(" AND a.category = :cat"); params.addValue("cat", cat); }
        if (adminId != null) { where.append(" AND a.admin_id = :admin"); params.addValue("admin", adminId); }
        if (targetType != null && !targetType.isBlank() && targetId != null && !targetId.isBlank()) {
            where.append(" AND a.target_type = :tt AND a.target_id = :tid");
            params.addValue("tt", targetType.strip().toUpperCase()).addValue("tid", targetId.strip());
        }
        List<Row> rows = query(where + " ORDER BY a.id DESC LIMIT :limit OFFSET :offset", params);
        boolean more = rows.size() > PAGE_SIZE;
        if (more) rows = rows.subList(0, PAGE_SIZE);
        List<AdminOption> admins = jdbc.query("""
                SELECT DISTINCT a.admin_id, u.nickname FROM admin_actions a LEFT JOIN users u ON u.id = a.admin_id ORDER BY a.admin_id""",
                new MapSqlParameterSource(), (rs, i) -> new AdminOption(rs.getLong("admin_id"), name(rs.getString("nickname"), rs.getLong("admin_id"))));
        return new Page(rows, p, more, AdminActionRoutes.CATEGORY_LABELS, admins);
    }

    /** 요약 화면용 최근 n줄 */
    @Transactional(readOnly = true)
    public List<Row> recent(int n) {
        return query(" ORDER BY a.id DESC LIMIT :limit", new MapSqlParameterSource("limit", n));
    }

    private List<Row> query(String tail, MapSqlParameterSource params) {
        List<Row> rows = jdbc.query("""
                SELECT a.id, a.created_at, a.admin_id, u.nickname, a.category, a.action, a.target_type, a.target_id, a.summary
                FROM admin_actions a LEFT JOIN users u ON u.id = a.admin_id""" + tail, params, (rs, i) -> new Row(
                rs.getLong("id"), rs.getTimestamp("created_at").toLocalDateTime(), rs.getLong("admin_id"),
                name(rs.getString("nickname"), rs.getLong("admin_id")), rs.getString("category"),
                AdminActionRoutes.CATEGORY_LABELS.getOrDefault(rs.getString("category"), rs.getString("category")),
                rs.getString("action"), AdminActionRoutes.label(rs.getString("action")),
                rs.getString("target_type") == null ? null : new Target(rs.getString("target_type"), rs.getString("target_id"), null, null),
                rs.getString("summary")));
        return withTargetNames(rows);
    }

    private static String name(String nickname, long id) {
        return nickname == null ? "회원 #" + id : nickname;
    }

    /** 대상 이름과 주소를 종류별로 한 번씩 모아 찾는다 */
    private List<Row> withTargetNames(List<Row> rows) {
        Map<String, List<String>> idsByType = rows.stream().filter(r -> r.target() != null && r.target().id() != null)
                .collect(Collectors.groupingBy(r -> r.target().type(), Collectors.mapping(r -> r.target().id(), Collectors.toList())));
        Map<String, String[]> found = new HashMap<>();   // "TYPE:id" → {이름, 주소}
        idsByType.forEach((type, ids) -> {
            List<Long> nums = ids.stream().filter(s -> s.matches("\\d{1,18}")).map(Long::parseLong).distinct().toList();
            String sql = switch (type) {
                case "STORE" -> "SELECT id, name, CONCAT('/store.html?id=', id) AS url FROM stores WHERE id IN (:ids)";
                case "POST" -> "SELECT id, title AS name, CONCAT('/post.html?id=', id) AS url FROM posts WHERE id IN (:ids)";
                case "COMMENT" -> "SELECT c.id, CONCAT('댓글 · ', p.title) AS name, CONCAT('/post.html?id=', c.post_id, '#comments') AS url FROM post_comments c JOIN posts p ON p.id = c.post_id WHERE c.id IN (:ids)";
                case "USER" -> "SELECT id, nickname AS name, CONCAT('/admin-users.html?id=', id) AS url FROM users WHERE id IN (:ids)";
                case "MARKET" -> "SELECT id, name, CONCAT('/market.html?id=', id) AS url FROM markets WHERE id IN (:ids)";
                case "NOTICE" -> "SELECT id, title AS name, CONCAT('/notice.html?id=', id) AS url FROM notices WHERE id IN (:ids)";
                default -> null;
            };
            if ("FACILITY".equals(type)) {
                jdbc.query("SELECT market_code, name FROM market_facilities WHERE market_code IN (:ids)", new MapSqlParameterSource("ids", ids),
                        rs -> { found.put("FACILITY:" + rs.getString("market_code"), new String[]{rs.getString("name"), "/admin-markets.html"}); });
                return;
            }
            if (sql == null || nums.isEmpty()) return;
            jdbc.query(sql, new MapSqlParameterSource("ids", nums),
                    rs -> { found.put(type + ":" + rs.getLong("id"), new String[]{rs.getString("name"), rs.getString("url")}); });
        });
        List<Row> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            if (r.target() == null) { out.add(r); continue; }
            String[] f = found.get(r.target().type() + ":" + r.target().id());
            out.add(new Row(r.id(), r.at(), r.adminId(), r.admin(), r.category(), r.categoryLabel(), r.action(), r.label(),
                    new Target(r.target().type(), r.target().id(), f == null ? null : f[0], f == null ? null : f[1]), r.summary()));
        }
        return out;
    }

    /** 1년 지난 기록 지우기 (새벽 정리) */
    public int cleanup(LocalDateTime now) {
        return jdbc.update("DELETE FROM admin_actions WHERE created_at < :before LIMIT 10000",
                new MapSqlParameterSource("before", now.minusYears(1)));
    }
}
