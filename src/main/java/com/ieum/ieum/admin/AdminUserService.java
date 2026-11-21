package com.ieum.ieum.admin;

import com.ieum.ieum.community.CommunityService;
import com.ieum.ieum.favorite.FavoriteService;
import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.mail.MailService;
import com.ieum.ieum.notification.NotificationService;
import com.ieum.ieum.policy.Policy;
import com.ieum.ieum.user.BanService;
import com.ieum.ieum.user.User;
import com.ieum.ieum.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 관리자 회원 관리 (ADM-02): 회원 목록·검색, 상세, 강제 탈퇴, 재가입 허용.
 * 작성 정지·풀기는 동네 이야기 관리(CommunityAdminService.suspend)와 같은 API 를 쓴다.
 *
 * 강제 탈퇴 (되돌릴 수 없음)
 *  1. 그 회원의 글·댓글을 모두 가린다 (가린 까닭: 관리자가 탈퇴 처리한 회원). 그 글·댓글에 접수된 신고는 '숨김 처리'
 *  2. 이메일·카카오 회원번호를 알아볼 수 없게 바꾼 값으로 막아 같은 계정으로 다시 가입하지 못하게 한다 (BanService)
 *  3. 스스로 탈퇴할 때처럼 개인정보를 지운다: 이메일·비밀번호·카카오 번호·관심 지역, 로그인 유지, 찜, 알림
 *  4. 이메일 회원에게 사유를 메일로 알린다 (처리가 끝난 뒤. 메일이 안 가도 탈퇴는 그대로)
 * 관리자 계정과 내 계정은 탈퇴시킬 수 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    public static final int PAGE_SIZE = 30;
    public static final String HIDE_REASON = "관리자가 이용약관 위반으로 탈퇴 처리한 회원의 글이에요.";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy년 M월 d일");

    public record Row(long id, String nickname, String email, String provider, String role, String status, boolean banned,
                      LocalDateTime createdAt, LocalDateTime lastLoginAt, int posts, int comments) {
    }

    public record Page(List<Row> items, Map<String, Integer> counts, int page, boolean hasMore) {
    }

    public record PostBrief(long id, String title, String status, LocalDateTime createdAt) {
    }

    public record Detail(Row user, String fullEmail, LocalDateTime agreedAt, int storeReports, int reportsAgainst,
                         List<PostBrief> recentPosts, BanService.Ban ban) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final BanService banService;
    private final CommunityService community;
    private final FavoriteService favoriteService;
    private final NotificationService notificationService;
    private final MailService mailService;
    private final com.ieum.ieum.auth.oauth.KakaoUnlinkService kakaoUnlink;
    private final Policy policy;

    private static final String ROW = """
            SELECT u.id, u.nickname, u.email, u.provider, u.role, u.status, u.created_at, u.last_login_at,
                   EXISTS (SELECT 1 FROM banned_identities b WHERE b.user_id = u.id) AS banned,
                   (SELECT COUNT(*) FROM posts p WHERE p.user_id = u.id AND p.status <> 'DELETED') AS posts,
                   (SELECT COUNT(*) FROM post_comments c WHERE c.user_id = u.id AND c.status <> 'DELETED') AS comments
              FROM users u""";

    /**
     * 목록. q: 닉네임 일부, 이메일(@가 있으면 정확히 같은 것), 회원 번호(숫자).
     * status: ''(전체) | ACTIVE | SUSPENDED | WITHDRAWN | BANNED(강제 탈퇴) | ADMIN(관리자)
     */
    @Transactional(readOnly = true)
    public Page list(String q, String status, int page) {
        String query = q == null ? "" : q.strip();
        String st = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
        int pg = Math.max(0, page);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("limit", PAGE_SIZE + 1).addValue("offset", pg * PAGE_SIZE);
        if (!query.isEmpty()) {
            if (query.contains("@")) {
                where.append(" AND u.email = :email");
                p.addValue("email", query.toLowerCase(Locale.ROOT));
            } else if (query.matches("\\d{1,18}")) {
                where.append(" AND (u.id = :id OR u.nickname LIKE :like)");
                p.addValue("id", Long.parseLong(query)).addValue("like", "%" + like(query) + "%");
            } else {
                where.append(" AND u.nickname LIKE :like");
                p.addValue("like", "%" + like(query) + "%");
            }
        }
        switch (st) {
            case "ACTIVE", "SUSPENDED" -> where.append(" AND u.status = '").append(st).append("'");
            case "WITHDRAWN" -> where.append(" AND u.status = 'WITHDRAWN' AND NOT EXISTS (SELECT 1 FROM banned_identities b WHERE b.user_id = u.id)");
            case "BANNED" -> where.append(" AND EXISTS (SELECT 1 FROM banned_identities b WHERE b.user_id = u.id)");
            case "ADMIN" -> where.append(" AND u.role = 'ADMIN'");
            default -> { }
        }
        List<Row> rows = jdbc.query(ROW + where + " ORDER BY u.id DESC LIMIT :limit OFFSET :offset", p, (rs, i) -> row(rs));
        boolean more = rows.size() > PAGE_SIZE;
        return new Page(more ? rows.subList(0, PAGE_SIZE) : rows, counts(), pg, more);
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> c = new LinkedHashMap<>();
        jdbc.query("""
                SELECT COUNT(*) AS total,
                       SUM(status = 'ACTIVE') AS active, SUM(status = 'SUSPENDED') AS suspended,
                       SUM(status = 'WITHDRAWN' AND NOT EXISTS (SELECT 1 FROM banned_identities b WHERE b.user_id = users.id)) AS withdrawn,
                       SUM(EXISTS (SELECT 1 FROM banned_identities b WHERE b.user_id = users.id)) AS banned,
                       SUM(role = 'ADMIN') AS admin
                  FROM users""", new MapSqlParameterSource(), rs -> {
            c.put("ALL", rs.getInt("total"));
            c.put("ACTIVE", rs.getInt("active"));
            c.put("SUSPENDED", rs.getInt("suspended"));
            c.put("WITHDRAWN", rs.getInt("withdrawn"));
            c.put("BANNED", rs.getInt("banned"));
            c.put("ADMIN", rs.getInt("admin"));
        });
        return c;
    }

    @Transactional(readOnly = true)
    public Detail detail(long id) {
        List<Row> rows = jdbc.query(ROW + " WHERE u.id = :id", new MapSqlParameterSource("id", id), (rs, i) -> row(rs));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, List.of("회원을 찾을 수 없어요."));
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        Map<String, Object> u = jdbc.queryForMap("SELECT email, agreed_at FROM users WHERE id = :id", p);
        int storeReports = count("SELECT COUNT(*) FROM store_reports WHERE user_id = :id", p);
        int against = count("""
                SELECT (SELECT COUNT(*) FROM community_reports r JOIN posts t ON r.target_type = 'POST' AND t.id = r.target_id WHERE t.user_id = :id)
                     + (SELECT COUNT(*) FROM community_reports r JOIN post_comments t ON r.target_type = 'COMMENT' AND t.id = r.target_id WHERE t.user_id = :id)""", p);
        List<PostBrief> posts = jdbc.query("""
                SELECT id, title, status, created_at FROM posts WHERE user_id = :id AND status <> 'DELETED' ORDER BY id DESC LIMIT 5""", p,
                (rs, i) -> new PostBrief(rs.getLong("id"), rs.getString("title"), rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime()));
        return new Detail(rows.getFirst(), (String) u.get("email"), toDateTime(u.get("agreed_at")), storeReports, against,
                posts, banService.banOf(id).orElse(null));
    }

    /** 강제 탈퇴 (되돌릴 수 없음) */
    @Transactional
    public Detail forceWithdraw(long adminId, long id, String rawReason) {
        String reason = rawReason == null ? "" : rawReason.strip();
        if (reason.length() < 5 || reason.length() > 300) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("탈퇴시키는 까닭을 5~300자로 적어 주세요. 본인에게 메일로 보내요."));
        }
        if (adminId == id) throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("내 계정은 여기서 탈퇴시킬 수 없어요."));
        // 회원 줄을 잠근다: 같은 회원을 두 관리자가 동시에 처리하거나, 그 사이 본인이 탈퇴하는 경우
        List<Map<String, Object>> locked = jdbc.queryForList("SELECT role, status FROM users WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", id));
        if (locked.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, List.of("회원을 찾을 수 없어요."));
        if ("WITHDRAWN".equals(locked.getFirst().get("status"))) throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("이미 탈퇴한 회원이에요."));
        if ("ADMIN".equals(locked.getFirst().get("role"))) throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("관리자 계정은 탈퇴시킬 수 없어요. 먼저 관리자 권한을 거둬 주세요."));
        User user = userRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        String email = user.getEmail();
        String nickname = user.getNickname();

        // 1. 글·댓글 가리기 + 그 글·댓글에 접수된 신고 정리
        MapSqlParameterSource p = new MapSqlParameterSource("id", id).addValue("why", HIDE_REASON).addValue("admin", adminId);
        jdbc.update("""
                UPDATE community_reports r JOIN posts t ON r.target_type = 'POST' AND t.id = r.target_id
                   SET r.status = 'HIDDEN', r.reviewed_by = :admin, r.reviewed_at = NOW()
                 WHERE t.user_id = :id AND r.status = 'RECEIVED'""", p);
        jdbc.update("""
                UPDATE community_reports r JOIN post_comments t ON r.target_type = 'COMMENT' AND t.id = r.target_id
                   SET r.status = 'HIDDEN', r.reviewed_by = :admin, r.reviewed_at = NOW()
                 WHERE t.user_id = :id AND r.status = 'RECEIVED'""", p);
        int posts = jdbc.update("""
                UPDATE posts SET status = 'HIDDEN', hidden_reason = :why, hidden_until = NULL
                 WHERE user_id = :id AND status IN ('VISIBLE', 'TEMP_HIDDEN')""", p);
        List<Long> commentedPosts = jdbc.queryForList("""
                SELECT DISTINCT post_id FROM post_comments WHERE user_id = :id AND status IN ('VISIBLE', 'TEMP_HIDDEN')""", p, Long.class);
        int comments = jdbc.update("""
                UPDATE post_comments SET status = 'HIDDEN', hidden_reason = :why
                 WHERE user_id = :id AND status IN ('VISIBLE', 'TEMP_HIDDEN')""", p);
        commentedPosts.forEach(community::recountComments);

        // 2. 재가입 막기 (지우기 전에 이메일·카카오 번호로)
        banService.ban(BanService.Kind.EMAIL, email, id, reason, adminId);
        if (user.getProvider() == com.ieum.ieum.user.AuthProvider.KAKAO) {
            banService.ban(BanService.Kind.KAKAO, user.getProviderId(), id, reason, adminId);
            // 카카오 연결도 끊는다 (KAKAO-UNLINK). 다시 로그인해도 같은 카카오 회원번호라 재가입 막기는 그대로 걸린다
            kakaoUnlink.unlinkAfterCommit(user.getProviderId(), id);
        }

        // 3. 스스로 탈퇴할 때와 같은 개인정보 삭제
        jdbc.update("DELETE FROM refresh_tokens WHERE user_id = :id", p);
        favoriteService.deleteAll(id);
        notificationService.deleteAll(id);
        user.withdraw();

        // 4. 메일 (처리가 끝난 뒤에만)
        if (email != null) {
            String text = mailText(nickname, reason);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        mailService.send(email, "[이음] 회원 탈퇴 처리 안내", text);
                    } catch (MailService.MailException e) {
                        log.warn("강제 탈퇴 안내 메일을 보내지 못했어요: 회원 {}", id);
                    }
                }
            });
        }
        log.info("관리자 {} 회원 {} 강제 탈퇴: 글 {}·댓글 {} 가림", adminId, id, posts, comments);
        userRepository.flush();
        return detail(id);
    }

    /** 재가입 허용 (강제 탈퇴 때 막은 이메일·카카오 계정을 푼다). 탈퇴 상태와 가린 글은 그대로 */
    @Transactional
    public Detail allowRejoin(long adminId, long id) {
        int n = banService.unban(id);
        if (n == 0) throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("재가입을 막은 회원이 아니에요."));
        log.info("관리자 {} 회원 {} 재가입 허용", adminId, id);
        return detail(id);
    }

    private String mailText(String nickname, String reason) {
        String contact = policy.contactEmail();
        return """
                안녕하세요, 이음입니다.

                %s에 이음 이용약관 위반으로 '%s' 계정을 탈퇴 처리했어요.

                사유: %s

                - 쓰신 글과 댓글은 다른 분들께 보이지 않게 가렸어요.
                - 같은 이메일로는 다시 가입할 수 없어요.
                - 이메일·비밀번호 등 개인정보는 탈퇴와 함께 지웠어요.

                처리에 이의가 있으면 %s 로 알려 주세요.

                이음 — 우리 동네의 오래된 이야기를 잇습니다
                """.formatted(LocalDateTime.now().format(WHEN), nickname, reason,
                contact == null || contact.isBlank() ? "개인정보 처리방침에 있는 운영자 연락처" : contact);
    }

    /** 드라이버에 따라 DATETIME이 LocalDateTime 또는 Timestamp로 온다 */
    private static LocalDateTime toDateTime(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime t) return t;
        if (v instanceof Timestamp t) return t.toLocalDateTime();
        throw new IllegalStateException("날짜 형식이 아니에요: " + v.getClass());
    }

    private Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp last = rs.getTimestamp("last_login_at");
        return new Row(rs.getLong("id"), rs.getString("nickname"), mask(rs.getString("email")), rs.getString("provider"),
                rs.getString("role"), rs.getString("status"), rs.getBoolean("banned"),
                rs.getTimestamp("created_at").toLocalDateTime(), last == null ? null : last.toLocalDateTime(),
                rs.getInt("posts"), rs.getInt("comments"));
    }

    /** 목록에는 이메일을 가려서 (ab***@gmail.com). 전체 주소는 상세에서만 */
    static String mask(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at < 0) return "***";
        String name = email.substring(0, at);
        return (name.length() <= 2 ? name.charAt(0) + "*" : name.substring(0, 2) + "***") + email.substring(at);
    }

    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private int count(String sql, MapSqlParameterSource p) {
        Integer n = jdbc.queryForObject(sql, p, Integer.class);
        return n == null ? 0 : n;
    }
}
