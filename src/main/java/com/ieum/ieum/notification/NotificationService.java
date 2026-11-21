package com.ieum.ieum.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.IntFunction;

/**
 * 알림 (NOTI-01). 이음 화면 안의 알림: 위쪽 종 아이콘(안 읽은 수)과 알림 화면(/notifications.html).
 *
 * 무엇을 알리나
 *   COMMENT          내 글에 다른 사람이 댓글 (안 읽은 동안은 한 줄로 묶어 '새 댓글 3개')
 *   REPORT_APPLIED   내 영업 정보 제보가 반영됨       REPORT_REJECTED  반려됨
 *   HIDDEN           내 글·댓글이 가려짐 (관리자 또는 신고 3건)   RESTORED  다시 보임
 *   RECOMMEND        '추천' 글을 관리자가 확인함 (지도에 추가·이미 있음·보류)
 * 공감은 알리지 않는다 (너무 잦아서).
 *
 * - 탈퇴한 회원에게는 만들지 않는다. 탈퇴하면 그 회원의 알림을 바로 지운다.
 * - 90일이 지난 알림은 매일 지운다 (TokenCleanupService).
 * - 알림을 만들다 실패해도 원래 일(댓글 쓰기, 제보 처리 등)은 그대로 되게, 부르는 쪽에서 send 만 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    public static final int PAGE_SIZE = 20;
    public static final int KEEP_DAYS = 90;

    public record Item(long id, String type, String title, String body, String link, int count, boolean read, LocalDateTime at) {
    }

    public record Page(List<Item> items, int unread, boolean hasMore) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    /** 알림 한 줄 */
    public void send(long userId, String type, String title, String body, String link) {
        MapSqlParameterSource p = params(userId, type, title, body, link).addValue("g", null);
        jdbc.update("""
                INSERT INTO notifications (user_id, type, title, body, link, group_key, created_at, updated_at)
                SELECT :u, :type, :title, :body, :link, :g, :now, :now FROM users WHERE id = :u AND status <> 'WITHDRAWN'""", p);
    }

    /**
     * 묶이는 알림: 같은 group 의 안 읽은 알림이 있으면 수를 하나 올리고 문구·시간을 새로 하고, 없으면 새로 만든다.
     * body 는 묶인 수로 문구를 만든다 (1 → '새 댓글이 달렸어요', 3 → '새 댓글 3개')
     */
    public void sendGrouped(long userId, String group, String type, String title, IntFunction<String> body, String link) {
        MapSqlParameterSource p = params(userId, type, title, null, link).addValue("g", group);
        List<Integer> open = jdbc.queryForList("""
                SELECT count FROM notifications WHERE user_id = :u AND group_key = :g AND read_at IS NULL
                ORDER BY id DESC LIMIT 1 FOR UPDATE""", p, Integer.class);
        if (!open.isEmpty()) {
            int n = open.getFirst() + 1;
            jdbc.update("""
                    UPDATE notifications SET count = :n, body = :body, title = :title, updated_at = :now
                     WHERE user_id = :u AND group_key = :g AND read_at IS NULL""",
                    p.addValue("n", n).addValue("body", cut(body.apply(n), 300)));
            return;
        }
        p.addValue("body", cut(body.apply(1), 300));
        jdbc.update("""
                INSERT INTO notifications (user_id, type, title, body, link, group_key, created_at, updated_at)
                SELECT :u, :type, :title, :body, :link, :g, :now, :now FROM users WHERE id = :u AND status <> 'WITHDRAWN'""", p);
    }

    @Transactional(readOnly = true)
    public Page list(long userId, int page) {
        int pg = Math.max(0, page);
        MapSqlParameterSource p = new MapSqlParameterSource("u", userId)
                .addValue("limit", PAGE_SIZE + 1).addValue("offset", pg * PAGE_SIZE);
        List<Item> rows = jdbc.query("""
                SELECT id, type, title, body, link, count, read_at, updated_at FROM notifications
                 WHERE user_id = :u ORDER BY updated_at DESC, id DESC LIMIT :limit OFFSET :offset""", p,
                (rs, i) -> new Item(rs.getLong("id"), rs.getString("type"), rs.getString("title"), rs.getString("body"),
                        rs.getString("link"), rs.getInt("count"), rs.getTimestamp("read_at") != null,
                        rs.getTimestamp("updated_at").toLocalDateTime()));
        boolean more = rows.size() > PAGE_SIZE;
        return new Page(more ? rows.subList(0, PAGE_SIZE) : rows, unread(userId), more);
    }

    @Transactional(readOnly = true)
    public int unread(long userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = :u AND read_at IS NULL",
                new MapSqlParameterSource("u", userId), Integer.class);
        return n == null ? 0 : n;
    }

    /** 하나 읽음 (내 것만) */
    @Transactional
    public void read(long userId, long id) {
        jdbc.update("UPDATE notifications SET read_at = NOW() WHERE id = :id AND user_id = :u AND read_at IS NULL",
                new MapSqlParameterSource("u", userId).addValue("id", id));
    }

    @Transactional
    public void readAll(long userId) {
        jdbc.update("UPDATE notifications SET read_at = NOW() WHERE user_id = :u AND read_at IS NULL", new MapSqlParameterSource("u", userId));
    }

    public int deleteAll(long userId) {
        return jdbc.update("DELETE FROM notifications WHERE user_id = :u", new MapSqlParameterSource("u", userId));
    }

    /** 오래된 알림 지우기 (매일) */
    public int cleanup(LocalDateTime now) {
        int n = jdbc.update("DELETE FROM notifications WHERE created_at < :before",
                new MapSqlParameterSource("before", Timestamp.valueOf(now.minusDays(KEEP_DAYS))));
        if (n > 0) log.info("오래된 알림 {}건을 지웠어요 ({}일 지남).", n, KEEP_DAYS);
        return n;
    }

    /** 글 제목 같은 것을 알림 문구에 넣을 때 길면 자른다 */
    public static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static MapSqlParameterSource params(long userId, String type, String title, String body, String link) {
        return new MapSqlParameterSource("u", userId).addValue("type", type).addValue("title", cut(title, 100))
                .addValue("body", cut(body, 300)).addValue("link", link).addValue("now", Timestamp.valueOf(LocalDateTime.now()));
    }
}
