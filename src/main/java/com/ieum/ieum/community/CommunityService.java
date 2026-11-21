package com.ieum.ieum.community;

import com.ieum.ieum.community.CommunityDtos.Author;
import com.ieum.ieum.community.CommunityDtos.CommentView;
import com.ieum.ieum.community.CommunityDtos.LikeResult;
import com.ieum.ieum.community.CommunityDtos.Place;
import com.ieum.ieum.community.CommunityDtos.PostDetail;
import com.ieum.ieum.community.CommunityDtos.PostPage;
import com.ieum.ieum.community.CommunityDtos.PostRequest;
import com.ieum.ieum.community.CommunityDtos.PostSummary;
import com.ieum.ieum.community.CommunityDtos.ReportRequest;
import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.global.security.AuthUser;
import com.ieum.ieum.policy.Policy;
import com.ieum.ieum.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 동네 이야기 게시판 (COM-01)
 *
 * - 보기는 누구나, 쓰기(글·댓글·공감·신고)는 약관에 동의한 회원만. 작성 정지(SUSPENDED) 회원은 쓸 수 없다.
 * - 하루에 글 10개, 댓글 50개, 신고 20건까지.
 * - 글은 글자만 저장하고 화면에서도 글자로만 보여 준다 (HTML로 해석하지 않음).
 * - 서로 다른 회원 3명이 신고하면 관리자가 보기 전까지 자동으로 가린다.
 * - 글을 지우면 status = DELETED (행은 남김: 댓글·신고 기록이 가리키고 있어서). 화면에서는 사라진다.
 * - 탈퇴한 회원의 글·댓글은 남고 작성자는 '탈퇴한 회원'으로 보인다.
 */
@Service
@RequiredArgsConstructor
public class CommunityService {

    static final int PAGE_SIZE = 20;
    static final int DAILY_POSTS = 10;
    static final int DAILY_COMMENTS = 50;
    static final int DAILY_REPORTS = 20;
    static final int AUTO_HIDE_REPORTS = 3;
    static final String AUTO_HIDE_REASON = "신고가 " + AUTO_HIDE_REPORTS + "건 쌓여 관리자가 확인할 때까지 가렸어요.";
    static final Set<String> PLACE_TYPES = Set.of("STORE", "MARKET", "FESTIVAL");
    static final String WITHDRAWN_NAME = "탈퇴한 회원";

    private final NamedParameterJdbcTemplate jdbc;
    private final Policy policy;
    private final com.ieum.ieum.notification.NotificationService notifications;
    private final com.ieum.ieum.community.photo.PostPhotoService photos;

    // ================================================================ 보기

    static final String SUMMARY_SELECT = """
            SELECT p.id, p.user_id, p.category, p.title, p.content, p.place_type, p.place_id, p.status,
                   p.recommend_status, p.recommend_note, p.hidden_reason, p.like_count, p.comment_count,
                   p.created_at, p.edited_at, u.nickname, u.status AS user_status,
                   COALESCE(s.name, m.name, f.name) AS place_name,
                   (SELECT ph.file_key FROM post_photos ph WHERE ph.post_id = p.id ORDER BY ph.sort_order, ph.id LIMIT 1) AS thumb_key,
                   (SELECT COUNT(*) FROM post_photos ph WHERE ph.post_id = p.id) AS photo_count
            FROM posts p
            JOIN users u ON u.id = p.user_id
            LEFT JOIN stores s ON p.place_type = 'STORE' AND s.id = p.place_id
            LEFT JOIN markets m ON p.place_type = 'MARKET' AND m.id = p.place_id
            LEFT JOIN festivals f ON p.place_type = 'FESTIVAL' AND f.id = p.place_id
            """;

    /** 목록. category: 빈 값 = 전체, q: 제목·내용 검색, placeType+placeId: 그곳에 붙은 이야기만 */
    @Transactional(readOnly = true)
    public PostPage list(String category, String q, String placeType, Long placeId, int page, Long viewerId) {
        String cat = category == null ? "" : category.strip().toUpperCase();
        if (!cat.isEmpty() && !CommunityDtos.CATEGORIES.containsKey(cat)) cat = "";
        String query = q == null ? "" : q.strip();
        if (query.length() > 40) query = query.substring(0, 40);
        String pType = placeType == null ? "" : placeType.strip().toUpperCase();
        boolean byPlace = PLACE_TYPES.contains(pType) && placeId != null;
        int p = Math.max(0, page);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("cat", cat).addValue("q", like(query)).addValue("hasQ", !query.isEmpty())
                .addValue("byPlace", byPlace).addValue("pType", pType).addValue("pId", byPlace ? placeId : 0L)
                .addValue("offset", p * PAGE_SIZE).addValue("size", PAGE_SIZE + 1);
        String where = """
                WHERE p.status = 'VISIBLE' AND (:cat = '' OR p.category = :cat)
                  AND (NOT :hasQ OR p.title LIKE :q ESCAPE '!' OR p.content LIKE :q ESCAPE '!')
                  AND (NOT :byPlace OR (p.place_type = :pType AND p.place_id = :pId))
                """;
        List<PostSummary> rows = jdbc.query(SUMMARY_SELECT + where + " ORDER BY p.id DESC LIMIT :offset, :size", params,
                (rs, i) -> summary(rs, viewerId));
        boolean hasNext = rows.size() > PAGE_SIZE;
        if (hasNext) rows = rows.subList(0, PAGE_SIZE);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM posts p " + where, params, Long.class);
        return new PostPage(rows, p, hasNext, total == null ? 0 : total);
    }

    /** 내 글 (지운 글 빼고, 가려진 글은 상태와 함께) */
    @Transactional(readOnly = true)
    public List<PostSummary> mine(AuthUser viewer) {
        if (viewer == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return jdbc.query(SUMMARY_SELECT + " WHERE p.user_id = :uid AND p.status <> 'DELETED' ORDER BY p.id DESC LIMIT 100",
                new MapSqlParameterSource("uid", viewer.id()), (rs, i) -> summary(rs, viewer.id()));
    }

    /**
     * 글 하나. 보이는 글은 누구나. 가려진 글은 글쓴이와 관리자만 (까닭과 함께), 그 밖에는 '찾을 수 없음'.
     */
    @Transactional(readOnly = true)
    public PostDetail detail(long id, AuthUser viewer) {
        Map<String, Object> row = postRow(id);
        String status = (String) row.get("status");
        long authorId = ((Number) row.get("user_id")).longValue();
        Long viewerId = viewer == null ? null : viewer.id();
        boolean admin = viewer != null && viewer.role() == UserRole.ADMIN;
        boolean mine = viewerId != null && viewerId == authorId;
        if ("DELETED".equals(status) || (!"VISIBLE".equals(status) && !mine && !admin)) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        List<CommentView> comments = jdbc.query("""
                SELECT c.id, c.user_id, c.content, c.status, c.created_at, u.nickname, u.status AS user_status
                FROM post_comments c JOIN users u ON u.id = c.user_id
                WHERE c.post_id = :id ORDER BY c.id
                """, new MapSqlParameterSource("id", id), (rs, i) -> {
            String st = rs.getString("status");
            boolean own = viewerId != null && viewerId == rs.getLong("user_id");
            boolean show = "VISIBLE".equals(st) || (admin && !"DELETED".equals(st));
            return new CommentView(rs.getLong("id"), show ? rs.getString("content") : null,
                    "VISIBLE".equals(st) ? "VISIBLE" : st, author(rs.getString("nickname"), rs.getString("user_status"), own),
                    time(rs, "created_at"));
        });
        boolean liked = viewerId != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COUNT(*) > 0 FROM post_likes WHERE post_id = :id AND user_id = :uid",
                new MapSqlParameterSource().addValue("id", id).addValue("uid", viewerId), Boolean.class));
        String cat = (String) row.get("category");
        String rs = (String) row.get("recommend_status");
        return new PostDetail(id, cat, CommunityDtos.CATEGORIES.get(cat), (String) row.get("title"), (String) row.get("content"),
                author((String) row.get("nickname"), (String) row.get("user_status"), mine),
                place((String) row.get("place_type"), (Number) row.get("place_id"), (String) row.get("place_name")),
                ((Number) row.get("like_count")).intValue(), ((Number) row.get("comment_count")).intValue(),
                (LocalDateTime) row.get("created_at"), (LocalDateTime) row.get("edited_at"), status,
                "VISIBLE".equals(status) ? null : (String) row.get("hidden_reason"),
                rs, rs == null ? null : CommunityDtos.RECOMMEND_STATUS.get(rs), (String) row.get("recommend_note"),
                liked, mine && "VISIBLE".equals(status), comments, photos.photosOf(id, "VISIBLE".equals(status)));
    }

    // ================================================================ 쓰기

    @Transactional
    public PostDetail create(AuthUser viewer, PostRequest req) {
        long uid = requireWriter(viewer);
        Valid v = validate(req);
        dailyLimit("SELECT COUNT(*) FROM posts WHERE user_id = :uid AND created_at >= CURDATE()", uid, DAILY_POSTS);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("uid", uid).addValue("cat", v.category)
                .addValue("title", v.title).addValue("content", v.content).addValue("pType", v.placeType)
                .addValue("pId", v.placeId).addValue("rs", "RECOMMEND".equals(v.category) ? "PENDING" : null);
        org.springframework.jdbc.support.GeneratedKeyHolder key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO posts (user_id, category, title, content, place_type, place_id, recommend_status)
                VALUES (:uid, :cat, :title, :content, :pType, :pId, :rs)
                """, params, key, new String[]{"id"});
        long id = key.getKey().longValue();
        photos.attach(uid, id, req == null || req.photos() == null ? List.of() : req.photos());   // COM-02
        return detail(id, viewer);
    }

    @Transactional
    public PostDetail update(AuthUser viewer, long id, PostRequest req) {
        long uid = requireWriter(viewer);
        Map<String, Object> row = postRow(id);
        if (!"VISIBLE".equals(row.get("status"))) throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        if (((Number) row.get("user_id")).longValue() != uid) throw new BusinessException(ErrorCode.NOT_AUTHOR);
        Valid v = validate(req);
        String rs = (String) row.get("recommend_status");
        if ("RECOMMEND".equals(v.category) && rs == null) rs = "PENDING";
        if (!"RECOMMEND".equals(v.category)) rs = null;
        jdbc.update("""
                UPDATE posts SET category = :cat, title = :title, content = :content, place_type = :pType, place_id = :pId,
                                 recommend_status = :rs, recommend_note = IF(:rs IS NULL, NULL, recommend_note), edited_at = NOW()
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("cat", v.category).addValue("title", v.title)
                .addValue("content", v.content).addValue("pType", v.placeType).addValue("pId", v.placeId).addValue("rs", rs));
        photos.attach(uid, id, req == null ? null : req.photos());   // null 이면 사진 그대로 (COM-02)
        return detail(id, viewer);
    }

    /** 글 지우기: 글쓴이 또는 관리자 */
    @Transactional
    public void delete(AuthUser viewer, long id) {
        if (viewer == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        Map<String, Object> row = postRow(id);
        if ("DELETED".equals(row.get("status"))) throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        boolean admin = isAdmin(viewer);
        if (!admin && ((Number) row.get("user_id")).longValue() != viewer.id()) throw new BusinessException(ErrorCode.NOT_AUTHOR);
        if (!admin) requireNotWithdrawn(viewer);
        // 지운 글은 내용까지 바로 지운다 (처리방침 5번). 댓글이 달린 글이라 줄은 남긴다
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        jdbc.update("UPDATE posts SET status = 'DELETED', title = '(지운 글)', content = '', hidden_reason = NULL WHERE id = :id", p);
        jdbc.update("DELETE FROM post_likes WHERE post_id = :id", p);
        photos.deleteForPost(id);   // 사진도 줄과 파일을 바로 지운다 (COM-02)
    }

    @Transactional
    public LikeResult like(AuthUser viewer, long id, boolean on) {
        long uid = requireWriter(viewer);
        Map<String, Object> row = visiblePost(id);
        if (((Number) row.get("user_id")).longValue() == uid) throw new BusinessException(ErrorCode.CANNOT_LIKE_OWN);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", id).addValue("uid", uid);
        if (on) jdbc.update("INSERT IGNORE INTO post_likes (post_id, user_id) VALUES (:id, :uid)", params);
        else jdbc.update("DELETE FROM post_likes WHERE post_id = :id AND user_id = :uid", params);
        jdbc.update("UPDATE posts SET like_count = (SELECT COUNT(*) FROM post_likes WHERE post_id = :id) WHERE id = :id", params);
        Integer count = jdbc.queryForObject("SELECT like_count FROM posts WHERE id = :id", params, Integer.class);
        return new LikeResult(on, count == null ? 0 : count);
    }

    @Transactional
    public PostDetail comment(AuthUser viewer, long postId, String content) {
        long uid = requireWriter(viewer);
        visiblePost(postId);
        String text = content == null ? "" : content.strip();
        if (text.isEmpty() || text.length() > 1000) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("댓글은 1~1000자로 써 주세요."));
        }
        dailyLimit("SELECT COUNT(*) FROM post_comments WHERE user_id = :uid AND created_at >= CURDATE()", uid, DAILY_COMMENTS);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("pid", postId).addValue("uid", uid).addValue("c", text);
        jdbc.update("INSERT INTO post_comments (post_id, user_id, content) VALUES (:pid, :uid, :c)", params);
        recountComments(postId);
        notifyComment(postId, uid);
        return detail(postId, viewer);
    }

    /** 내 글에 다른 사람이 댓글을 달면 글쓴이에게 알림 (NOTI-01). 안 읽은 동안은 한 줄로 묶어 '새 댓글 N개' */
    private void notifyComment(long postId, long commenterId) {
        Map<String, Object> post = jdbc.queryForMap("SELECT user_id, title FROM posts WHERE id = :pid", new MapSqlParameterSource("pid", postId));
        long author = ((Number) post.get("user_id")).longValue();
        if (author == commenterId) return;   // 내 글에 내가 단 댓글
        String title = com.ieum.ieum.notification.NotificationService.cut((String) post.get("title"), 40);
        notifications.sendGrouped(author, "POST_COMMENT:" + postId, "COMMENT", "내 글에 댓글이 달렸어요",
                n -> n == 1 ? "‘" + title + "’에 새 댓글이 달렸어요." : "‘" + title + "’에 새 댓글 " + n + "개",
                "/post.html?id=" + postId + "#comments");
    }

    @Transactional
    public PostDetail deleteComment(AuthUser viewer, long commentId) {
        if (viewer == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT post_id, user_id, status FROM post_comments WHERE id = :id",
                new MapSqlParameterSource("id", commentId));
        if (rows.isEmpty() || "DELETED".equals(rows.getFirst().get("status"))) throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        Map<String, Object> c = rows.getFirst();
        boolean admin = isAdmin(viewer);
        if (!admin && ((Number) c.get("user_id")).longValue() != viewer.id()) throw new BusinessException(ErrorCode.NOT_AUTHOR);
        if (!admin) requireNotWithdrawn(viewer);
        long postId = ((Number) c.get("post_id")).longValue();
        jdbc.update("UPDATE post_comments SET status = 'DELETED', content = '', hidden_reason = NULL WHERE id = :id", new MapSqlParameterSource("id", commentId));
        recountComments(postId);
        return detail(postId, viewer);
    }

    /** 신고. 같은 글·댓글은 한 번만. 서로 다른 회원 3명이 신고하면 자동으로 가린다 */
    @Transactional
    public void report(AuthUser viewer, ReportRequest req) {
        long uid = requireWriter(viewer);
        String type = req == null || req.targetType() == null ? "" : req.targetType().strip().toUpperCase();
        String reason = req == null || req.reason() == null ? "" : req.reason().strip().toUpperCase();
        String content = req == null || req.content() == null ? null : req.content().strip();
        if (!Set.of("POST", "COMMENT").contains(type) || req.targetId() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("신고할 글이나 댓글을 확인해 주세요."));
        }
        if (!CommunityDtos.REPORT_REASONS.containsKey(reason)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("신고 까닭을 골라 주세요."));
        }
        if (content != null && content.length() > 300) throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("설명은 300자까지 쓸 수 있어요."));
        if (content != null && content.isEmpty()) content = null;
        String table = "POST".equals(type) ? "posts" : "post_comments";
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT user_id, status FROM " + table + " WHERE id = :id",
                new MapSqlParameterSource("id", req.targetId()));
        if (rows.isEmpty() || !"VISIBLE".equals(rows.getFirst().get("status"))) {
            throw new BusinessException("POST".equals(type) ? ErrorCode.POST_NOT_FOUND : ErrorCode.COMMENT_NOT_FOUND);
        }
        if (((Number) rows.getFirst().get("user_id")).longValue() == uid) throw new BusinessException(ErrorCode.CANNOT_REPORT_OWN);
        dailyLimit("SELECT COUNT(*) FROM community_reports WHERE user_id = :uid AND created_at >= CURDATE()", uid, DAILY_REPORTS);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("type", type).addValue("tid", req.targetId())
                .addValue("uid", uid).addValue("reason", reason).addValue("content", content).addValue("hide", AUTO_HIDE_REASON)
                .addValue("limit", AUTO_HIDE_REPORTS);
        try {
            jdbc.update("""
                    INSERT INTO community_reports (target_type, target_id, user_id, reason, content)
                    VALUES (:type, :tid, :uid, :reason, :content)
                    """, params);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_COMMUNITY_REPORT);
        }
        jdbc.update("UPDATE " + table + " SET report_count = (SELECT COUNT(*) FROM community_reports WHERE target_type = :type AND target_id = :tid) WHERE id = :tid", params);
        int changed = jdbc.update("UPDATE " + table + " SET status = 'HIDDEN', hidden_reason = :hide WHERE id = :tid AND status = 'VISIBLE' AND report_count >= :limit", params);
        if (changed > 0 && "COMMENT".equals(type)) {
            Long postId = jdbc.queryForObject("SELECT post_id FROM post_comments WHERE id = :tid", params, Long.class);
            if (postId != null) recountComments(postId);
        }
        if (changed > 0) {   // 글쓴이에게 알림 (NOTI-01)
            long postId = "POST".equals(type) ? req.targetId()
                    : jdbc.queryForObject("SELECT post_id FROM post_comments WHERE id = :tid", params, Long.class);
            notifyHidden(((Number) rows.getFirst().get("user_id")).longValue(), type, postId, AUTO_HIDE_REASON, false);
        }
    }

    /** 내 글·댓글이 가려졌을 때 / 다시 보일 때 글쓴이에게 알림 (NOTI-01). 관리자 화면(CommunityAdminService)도 쓴다 */
    void notifyHidden(long authorId, String type, long postId, String reason, boolean restored) {
        String what = "POST".equals(type) ? "글" : "댓글";
        if (restored) {
            notifications.send(authorId, "RESTORED", "내 " + what + "이 다시 보여요", "관리자가 확인하고 다시 보이게 했어요.", "/post.html?id=" + postId);
        } else {
            notifications.send(authorId, "HIDDEN", "내 " + what + "이 가려졌어요", reason, "/post.html?id=" + postId);
        }
    }

    // ================================================================ 도움

    record Valid(String category, String title, String content, String placeType, Long placeId) {
    }

    Valid validate(PostRequest req) {
        List<String> errors = new ArrayList<>();
        String category = req == null || req.category() == null ? "" : req.category().strip().toUpperCase();
        String title = req == null || req.title() == null ? "" : req.title().strip().replaceAll("\\s+", " ");
        String content = req == null || req.content() == null ? "" : req.content().strip().replace("\r\n", "\n");
        if (!CommunityDtos.CATEGORIES.containsKey(category)) errors.add("말머리를 골라 주세요.");
        if (title.length() < 2 || title.length() > 60) errors.add("제목은 2~60자로 써 주세요.");
        if (content.length() < 5 || content.length() > 3000) errors.add("내용은 5~3000자로 써 주세요.");
        String pType = req == null || req.placeType() == null || req.placeType().isBlank() ? null : req.placeType().strip().toUpperCase();
        Long pId = pType == null ? null : req.placeId();
        if (!errors.isEmpty()) throw new BusinessException(ErrorCode.INVALID_INPUT, errors);
        if ("RECOMMEND".equals(category)) {   // 추천은 아직 이음에 없는 곳이라 붙이지 않는다
            pType = null;
            pId = null;
        }
        if (pType != null) {
            if (!PLACE_TYPES.contains(pType) || pId == null || placeName(pType, pId) == null) {
                throw new BusinessException(ErrorCode.PLACE_NOT_FOUND);
            }
        }
        return new Valid(category, title, content, pType, pId);
    }

    String placeName(String type, long id) {
        String sql = switch (type) {
            case "STORE" -> "SELECT name FROM stores WHERE id = :id AND is_visible = 1";
            case "MARKET" -> "SELECT name FROM markets WHERE id = :id";
            default -> "SELECT name FROM festivals WHERE id = :id";
        };
        List<String> names = jdbc.queryForList(sql, new MapSqlParameterSource("id", id), String.class);
        return names.isEmpty() ? null : names.getFirst();
    }

    /** 쓸 수 있는 회원인지: 로그인, 탈퇴 안 함, 작성 정지 아님, 지금 판 약관에 동의함. 회원 번호를 돌려준다 */
    long requireWriter(AuthUser viewer) {
        if (viewer == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status, agreed_policy_version FROM users WHERE id = :id",
                new MapSqlParameterSource("id", viewer.id()));
        if (rows.isEmpty() || "WITHDRAWN".equals(rows.getFirst().get("status"))) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if ("SUSPENDED".equals(rows.getFirst().get("status"))) throw new BusinessException(ErrorCode.WRITE_SUSPENDED);
        if (((Number) rows.getFirst().get("agreed_policy_version")).intValue() < policy.version()) {
            throw new BusinessException(ErrorCode.AGREEMENT_REQUIRED);
        }
        return viewer.id();
    }

    private void requireNotWithdrawn(AuthUser viewer) {
        String status = jdbc.queryForList("SELECT status FROM users WHERE id = :id", new MapSqlParameterSource("id", viewer.id()), String.class)
                .stream().findFirst().orElse("WITHDRAWN");
        if ("WITHDRAWN".equals(status)) throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }

    /** 관리자인지 DB로 한 번 더 확인 (토큰의 권한만 믿지 않음) */
    boolean isAdmin(AuthUser viewer) {
        if (viewer == null || viewer.role() != UserRole.ADMIN) return false;
        return jdbc.queryForList("SELECT role FROM users WHERE id = :id AND status = 'ACTIVE'",
                new MapSqlParameterSource("id", viewer.id()), String.class).contains("ADMIN");
    }

    private void dailyLimit(String countSql, long uid, int limit) {
        Long n = jdbc.queryForObject(countSql, new MapSqlParameterSource("uid", uid), Long.class);
        if (n != null && n >= limit) throw new BusinessException(ErrorCode.COMMUNITY_DAILY_LIMIT);
    }

    private Map<String, Object> postRow(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(SUMMARY_SELECT + " WHERE p.id = :id", new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        Map<String, Object> row = rows.getFirst();
        row.replaceAll((k, v) -> v instanceof Timestamp t ? t.toLocalDateTime() : v);
        return row;
    }

    private Map<String, Object> visiblePost(long id) {
        Map<String, Object> row = postRow(id);
        if (!"VISIBLE".equals(row.get("status"))) throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        return row;
    }

    public void recountComments(long postId) {
        jdbc.update("UPDATE posts SET comment_count = (SELECT COUNT(*) FROM post_comments WHERE post_id = :id AND status = 'VISIBLE') WHERE id = :id",
                new MapSqlParameterSource("id", postId));
    }

    static String like(String q) {
        return "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    static Author author(String nickname, String userStatus, boolean mine) {
        boolean withdrawn = "WITHDRAWN".equals(userStatus);
        return new Author(withdrawn ? WITHDRAWN_NAME : nickname, withdrawn, mine);
    }

    static Place place(String type, Number id, String name) {
        if (type == null || id == null) return null;
        String url = switch (type) {
            case "STORE" -> "/store.html?id=" + id;
            case "MARKET" -> "/market.html?id=" + id;
            default -> "/festival.html?id=" + id;
        };
        return new Place(type, id.longValue(), name == null ? "(지금은 볼 수 없는 곳)" : name, name == null ? null : url);
    }

    static LocalDateTime time(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }

    static String excerpt(String content) {
        String one = content == null ? "" : content.replaceAll("\\s+", " ").strip();
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }

    static PostSummary summary(ResultSet rs, Long viewerId) throws SQLException {
        String cat = rs.getString("category");
        String rs2 = rs.getString("recommend_status");
        boolean mine = viewerId != null && viewerId == rs.getLong("user_id");
        long pid = rs.getLong("place_id");
        return new PostSummary(rs.getLong("id"), cat, CommunityDtos.CATEGORIES.get(cat), rs.getString("title"),
                excerpt(rs.getString("content")), author(rs.getString("nickname"), rs.getString("user_status"), mine),
                place(rs.getString("place_type"), rs.wasNull() ? null : pid, rs.getString("place_name")),
                rs.getInt("like_count"), rs.getInt("comment_count"), time(rs, "created_at"), rs.getTimestamp("edited_at") != null,
                rs.getString("status"), rs2, rs2 == null ? null : CommunityDtos.RECOMMEND_STATUS.get(rs2),
                "VISIBLE".equals(rs.getString("status")) ? com.ieum.ieum.community.photo.PostPhotoService.thumbUrl(rs.getString("thumb_key")) : null,
                rs.getInt("photo_count"));
    }
}
