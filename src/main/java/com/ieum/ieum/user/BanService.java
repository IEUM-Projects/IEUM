package com.ieum.ieum.user;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 재가입 막기 (ADM-02). 관리자가 강제 탈퇴시킨 회원의 이메일·카카오 회원번호를
 * 서버 비밀 키로 HMAC-SHA256 한 값만 banned_identities 에 둔다. 원래 이메일은 남기지 않는다.
 *
 * 비밀 키: ieum.ban.hash-key (없으면 jwt.secret). 키를 바꾸면 그전에 막은 계정이 풀리므로 바꾸지 않는다.
 */
@Service
public class BanService {

    public enum Kind { EMAIL, KAKAO }

    public record Ban(String reason, LocalDateTime at, String bannedBy) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final byte[] key;

    public BanService(NamedParameterJdbcTemplate jdbc,
                      @Value("${ieum.ban.hash-key:${jwt.secret:}}") String key) {
        this.jdbc = jdbc;
        this.key = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    }

    /** 이메일은 소문자·앞뒤 빈칸 없이 맞춘 뒤 바꾼다 (가입할 때와 같은 기준) */
    String hash(Kind kind, String value) {
        if (key.length == 0) throw new IllegalStateException("ieum.ban.hash-key 또는 jwt.secret 이 필요해요.");
        String v = kind == Kind.EMAIL ? value.strip().toLowerCase(Locale.ROOT) : value.strip();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((kind.name() + ":" + v).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean isBanned(Kind kind, String value) {
        if (value == null || value.isBlank()) return false;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM banned_identities WHERE kind = :k AND identity_hash = :h",
                new MapSqlParameterSource("k", kind.name()).addValue("h", hash(kind, value)), Integer.class);
        return n != null && n > 0;
    }

    public void ban(Kind kind, String value, long userId, String reason, Long adminId) {
        if (value == null || value.isBlank()) return;
        jdbc.update("""
                INSERT IGNORE INTO banned_identities (kind, identity_hash, user_id, reason, banned_by)
                VALUES (:k, :h, :u, :r, :a)""",
                new MapSqlParameterSource("k", kind.name()).addValue("h", hash(kind, value)).addValue("u", userId)
                        .addValue("r", reason).addValue("a", adminId));
    }

    /** 재가입 허용: 그 회원으로 막은 것을 모두 푼다. 푼 개수 */
    public int unban(long userId) {
        return jdbc.update("DELETE FROM banned_identities WHERE user_id = :u", new MapSqlParameterSource("u", userId));
    }

    public Optional<Ban> banOf(long userId) {
        List<Ban> rows = jdbc.query("""
                SELECT b.reason, b.created_at, a.nickname FROM banned_identities b LEFT JOIN users a ON a.id = b.banned_by
                 WHERE b.user_id = :u ORDER BY b.id LIMIT 1""", new MapSqlParameterSource("u", userId),
                (rs, i) -> new Ban(rs.getString("reason"), rs.getTimestamp("created_at").toLocalDateTime(), rs.getString("nickname")));
        return rows.stream().findFirst();
    }
}
