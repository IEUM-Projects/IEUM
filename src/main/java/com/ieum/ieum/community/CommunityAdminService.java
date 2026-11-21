package com.ieum.ieum.community;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 관리자 › 게시판 관리 (COM-01)
 *
 * 탭
 *   REPORTED   신고 접수: 아직 처리하지 않은 신고가 있는 글·댓글 (자동으로 가려진 것 포함)
 *   HIDDEN     가린 글·댓글: 관리자가 가렸거나(HIDDEN) 임시 숨김(TEMP_HIDDEN)인 것. 임시 숨김은 끝나는 날과 함께
 *   RECOMMEND  추천 처리: 말머리 '추천' 글 (확인 전 먼저)
 * 처리
 *   가리기(hide): 신고가 맞음 → 숨김, 그 대상의 접수 신고는 '숨김 처리'
 *   임시 숨김(temp): 가게 등 권리를 침해당했다는 요청 → 30일 동안 가림 (정보통신망법 제44조의2 임시조치)
 *   되돌리기(restore): 다시 보이게, 접수 신고는 '문제없음'
 *   추천 처리: 지도에 추가 / 이미 있음 / 보류 + 글쓴이에게 보이는 메모
 *   작성 정지(suspend): 회원의 글·댓글·제보 쓰기를 막음 (로그인·보기는 됨)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityAdminService {

    static final int TEMP_HIDE_DAYS = 30;
    static final Set<String> TARGETS = Set.of("POST", "COMMENT");

    private final NamedParameterJdbcTemplate jdbc;
    private final CommunityService community;
    private final com.ieum.ieum.notification.NotificationService notifications;
    private final com.ieum.ieum.community.photo.PostPhotoService photos;

    public record Target(String type, long id, Long postId, String category, String title, String content,
                         String author, Long authorId, String authorStatus, String status, String hiddenReason,
                         LocalDate hiddenUntil, boolean tempExpired, int reportCount, Map<String, Long> reasons,
                         List<String> reportNotes, LocalDateTime createdAt, String recommendStatus, String recommendNote,
                         List<com.ieum.ieum.community.photo.PostPhotoService.PhotoView> photos) {
    }

    public record TargetPage(List<Target> items, Map<String, Long> counts) {
    }

    @Transactional(readOnly = true)
    public TargetPage list(String tab) {
        List<Target> items = switch (tab == null ? "REPORTED" : tab) {
            case "HIDDEN" -> hiddenTargets();
            case "RECOMMEND" -> recommendPosts();
            case "REPORTED" -> reportedTargets();
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("탭을 확인해 주세요."));
        };
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("REPORTED", count("""
                SELECT COUNT(*) FROM (SELECT DISTINCT target_type, target_id FROM community_reports WHERE status = 'RECEIVED') x"""));
        counts.put("HIDDEN", count("SELECT (SELECT COUNT(*) FROM posts WHERE status IN ('HIDDEN','TEMP_HIDDEN')) + (SELECT COUNT(*) FROM post_comments WHERE status IN ('HIDDEN','TEMP_HIDDEN'))"));
        counts.put("RECOMMEND", count("SELECT COUNT(*) FROM posts WHERE category = 'RECOMMEND' AND status = 'VISIBLE' AND recommend_status = 'PENDING'"));
        return new TargetPage(items, counts);
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return n == null ? 0 : n;
    }

    private List<Target> reportedTargets() {
        List<Map<String, Object>> keys = jdbc.queryForList("""
                SELECT target_type, target_id, MIN(id) AS first_id FROM community_reports WHERE status = 'RECEIVED'
                GROUP BY target_type, target_id ORDER BY first_id LIMIT 100
                """, new MapSqlParameterSource());
        return keys.stream().map(k -> target((String) k.get("target_type"), ((Number) k.get("target_id")).longValue())).toList();
    }

    private List<Target> hiddenTargets() {
        List<Target> out = new java.util.ArrayList<>();
        jdbc.queryForList("SELECT id FROM posts WHERE status IN ('HIDDEN','TEMP_HIDDEN') ORDER BY updated_at DESC LIMIT 100",
                new MapSqlParameterSource(), Long.class).forEach(id -> out.add(target("POST", id)));
        jdbc.queryForList("SELECT id FROM post_comments WHERE status IN ('HIDDEN','TEMP_HIDDEN') ORDER BY updated_at DESC LIMIT 100",
                new MapSqlParameterSource(), Long.class).forEach(id -> out.add(target("COMMENT", id)));
        return out;
    }

    private List<Target> recommendPosts() {
        return jdbc.queryForList("""
                SELECT id FROM posts WHERE category = 'RECOMMEND' AND status = 'VISIBLE'
                ORDER BY recommend_status = 'PENDING' DESC, id DESC LIMIT 100
                """, new MapSqlParameterSource(), Long.class).stream().map(id -> target("POST", id)).toList();
    }

    Target target(String type, long id) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("type", type).addValue("id", id);
        Map<String, Object> r = "POST".equals(type)
                ? one("""
                    SELECT t.id, t.id AS post_id, t.category, t.title, t.content, t.status, t.hidden_reason, t.hidden_until,
                           t.report_count, t.created_at, t.recommend_status, t.recommend_note, u.id AS author_id, u.nickname, u.status AS user_status
                    FROM posts t JOIN users u ON u.id = t.user_id WHERE t.id = :id""", p)
                : one("""
                    SELECT t.id, t.post_id, NULL AS category, p.title, t.content, t.status, t.hidden_reason, NULL AS hidden_until,
                           t.report_count, t.created_at, NULL AS recommend_status, NULL AS recommend_note, u.id AS author_id, u.nickname, u.status AS user_status
                    FROM post_comments t JOIN posts p ON p.id = t.post_id JOIN users u ON u.id = t.user_id WHERE t.id = :id""", p);
        Map<String, Long> reasons = new LinkedHashMap<>();
        jdbc.query("SELECT reason, COUNT(*) AS n FROM community_reports WHERE target_type = :type AND target_id = :id GROUP BY reason ORDER BY n DESC",
                p, rs -> { reasons.put(CommunityDtos.REPORT_REASONS.getOrDefault(rs.getString("reason"), rs.getString("reason")), rs.getLong("n")); });
        List<String> notes = jdbc.queryForList("SELECT content FROM community_reports WHERE target_type = :type AND target_id = :id AND content IS NOT NULL ORDER BY id DESC LIMIT 5",
                p, String.class);
        LocalDate until = r.get("hidden_until") instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) r.get("hidden_until");
        String status = (String) r.get("status");
        boolean withdrawn = "WITHDRAWN".equals(r.get("user_status"));
        return new Target(type, id, ((Number) r.get("post_id")).longValue(), (String) r.get("category"), (String) r.get("title"),
                (String) r.get("content"), withdrawn ? CommunityService.WITHDRAWN_NAME : (String) r.get("nickname"),
                ((Number) r.get("author_id")).longValue(), (String) r.get("user_status"), status, (String) r.get("hidden_reason"),
                until, "TEMP_HIDDEN".equals(status) && until != null && !until.isAfter(LocalDate.now()),
                ((Number) r.get("report_count")).intValue(), reasons, notes, (LocalDateTime) r.get("created_at"),
                (String) r.get("recommend_status"), (String) r.get("recommend_note"),
                // 글의 사진 (COM-02). 가려진 글은 서명 붙은 주소라 관리자 화면에서도 보인다
                "POST".equals(type) && !"DELETED".equals(status) ? photos.photosOf(id, "VISIBLE".equals(status)) : List.of());
    }

    private Map<String, Object> one(String sql, MapSqlParameterSource p) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, p);
        if (rows.isEmpty()) throw new BusinessException("POST".equals(p.getValue("type")) ? ErrorCode.POST_NOT_FOUND : ErrorCode.COMMENT_NOT_FOUND);
        Map<String, Object> row = rows.getFirst();
        row.replaceAll((k, v) -> v instanceof java.sql.Timestamp t ? t.toLocalDateTime() : v);
        return row;
    }

    // ---------------------------------------------------------------- 처리

    /** 가리기. temp = 권리 침해 주장에 따른 임시 숨김 (30일) */
    @Transactional
    public Target hide(long adminId, String type, long id, boolean temp, String reason) {
        String t = checkType(type);
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > 300) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("가리는 까닭을 1~300자로 적어 주세요 (글쓴이에게 보여요)."));
        }
        Target before = target(t, id);
        if ("DELETED".equals(before.status())) throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id).addValue("why", why)
                .addValue("st", temp ? "TEMP_HIDDEN" : "HIDDEN").addValue("until", temp ? LocalDate.now().plusDays(TEMP_HIDE_DAYS) : null)
                .addValue("type", t).addValue("admin", adminId);
        if ("POST".equals(t)) {
            jdbc.update("UPDATE posts SET status = :st, hidden_reason = :why, hidden_until = :until WHERE id = :id", p);
        } else {
            jdbc.update("UPDATE post_comments SET status = :st, hidden_reason = :why WHERE id = :id", p);
            community.recountComments(before.postId());
        }
        jdbc.update("UPDATE community_reports SET status = 'HIDDEN', reviewed_by = :admin, reviewed_at = NOW() WHERE target_type = :type AND target_id = :id AND status = 'RECEIVED'", p);
        if (before.authorId() != null && "VISIBLE".equals(before.status())) {   // 알림 (NOTI-01): 보이던 것을 가렸을 때만
            community.notifyHidden(before.authorId(), t, before.postId(), temp ? "권리 침해 신고로 30일 동안 임시로 가렸어요. " + why : why, false);
        }
        log.info("게시판 {} {} 가림 (관리자 {}, {})", t, id, adminId, temp ? "임시" : "신고");
        return target(t, id);
    }

    /** 다시 보이게 (신고가 문제없음, 임시 숨김이 끝남 등). 접수된 신고는 '문제없음' */
    @Transactional
    public Target restore(long adminId, String type, long id) {
        String t = checkType(type);
        Target before = target(t, id);
        if ("DELETED".equals(before.status())) throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id).addValue("type", t).addValue("admin", adminId);
        if ("POST".equals(t)) {
            jdbc.update("UPDATE posts SET status = 'VISIBLE', hidden_reason = NULL, hidden_until = NULL WHERE id = :id", p);
        } else {
            jdbc.update("UPDATE post_comments SET status = 'VISIBLE', hidden_reason = NULL WHERE id = :id", p);
            community.recountComments(before.postId());
        }
        jdbc.update("UPDATE community_reports SET status = 'DISMISSED', reviewed_by = :admin, reviewed_at = NOW() WHERE target_type = :type AND target_id = :id AND status = 'RECEIVED'", p);
        if (before.authorId() != null && !"VISIBLE".equals(before.status())) {   // 가려졌던 것을 다시 보이게 했을 때만 (NOTI-01)
            community.notifyHidden(before.authorId(), t, before.postId(), null, true);
        }
        return target(t, id);
    }

    @Transactional
    public Target recommend(long id, String status, String note) {
        String st = status == null ? "" : status.strip().toUpperCase();
        if (!CommunityDtos.RECOMMEND_STATUS.containsKey(st)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("처리 상태를 골라 주세요."));
        }
        String memo = note == null || note.isBlank() ? null : note.strip();
        if (memo != null && memo.length() > 300) throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("메모는 300자까지 쓸 수 있어요."));
        List<Map<String, Object>> was = jdbc.queryForList("SELECT user_id, recommend_status FROM posts WHERE id = :id AND category = 'RECOMMEND'",
                new MapSqlParameterSource("id", id));
        int n = jdbc.update("UPDATE posts SET recommend_status = :st, recommend_note = :memo WHERE id = :id AND category = 'RECOMMEND' AND status <> 'DELETED'",
                new MapSqlParameterSource().addValue("id", id).addValue("st", st).addValue("memo", memo));
        if (n == 0) throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        // 확인 결과가 새로 정해지면 글쓴이에게 알림 (NOTI-01). '확인 전'으로 되돌릴 때는 알리지 않는다
        if (!"PENDING".equals(st) && !st.equals(was.getFirst().get("recommend_status"))) {
            notifications.send(((Number) was.getFirst().get("user_id")).longValue(), "RECOMMEND", "추천한 곳을 확인했어요",
                    CommunityDtos.RECOMMEND_STATUS.get(st) + (memo == null ? "" : " · " + memo), "/post.html?id=" + id);
        }
        return target("POST", id);
    }

    /** 작성 정지 / 풀기. 관리자는 정지할 수 없다 */
    @Transactional
    public String suspend(long userId, boolean on) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT role, status FROM users WHERE id = :id", new MapSqlParameterSource("id", userId));
        if (rows.isEmpty() || "WITHDRAWN".equals(rows.getFirst().get("status"))) throw new BusinessException(ErrorCode.NOT_FOUND);
        if ("ADMIN".equals(rows.getFirst().get("role"))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("관리자는 작성 정지할 수 없어요."));
        }
        String st = on ? "SUSPENDED" : "ACTIVE";
        jdbc.update("UPDATE users SET status = :st WHERE id = :id", new MapSqlParameterSource().addValue("st", st).addValue("id", userId));
        log.info("회원 {} 작성 정지 {}", userId, on ? "시작" : "풀기");
        return st;
    }

    private static String checkType(String type) {
        String t = type == null ? "" : type.strip().toUpperCase();
        if (!TARGETS.contains(t)) throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("글인지 댓글인지 확인해 주세요."));
        return t;
    }
}
